# /// script
# requires-python = ">=3.10"
# dependencies = ["telethon>=1.36"]
# ///
"""
«Собеседник» для проверок Reach на устройстве: второй аккаунт на тестовых серверах Telegram (test DC).

Агент гоняет Reach (сборка `dctest`, «Reach Test») под одним тестовым аккаунтом, а этим скриптом
шлёт ему входящие: текст, медиа, реакции, правки, удаления, группы. Боевые аккаунты не участвуют.

Аккаунты на test DC. Номера 99966XYYYY с кодом XXXXX Telegram отключил (tdlib/td#3083, 2026).
Нужен настоящий номер, зарегистрированный на test DC в официальном iOS-приложении:
10 тапов по «Настройки» → Accounts → Login to another account → Test. Это отдельная регистрация,
боевой аккаунт на том же номере не затрагивается. Коды входа приходят в эту тестовую сессию.

Ключи — те же APP_ID / APP_HASH, что у приложения (ADR-0003): из окружения или из
~/.config/reach/peer.env (строки KEY=VALUE, вне репозитория).
Сессия хранится вне репозитория: ~/.local/share/reach-peer/<номер>.session

Вход в два шага (код приходит в тестовую сессию на iPhone):
    uv run scripts/peer.py --phone +77001234567 login                 # запросить код
    uv run scripts/peer.py --phone +77001234567 login --code 12345    # завершить вход

Дальше:
    uv run scripts/peer.py me
    uv run scripts/peer.py contact +77007654321 "Reach Agent"   # чтобы адресовать по номеру
    uv run scripts/peer.py send +77007654321 "привет"
    uv run scripts/peer.py photo +77007654321 ./cat.jpg --caption "кот"
    uv run scripts/peer.py history +77007654321 -n 5            # id сообщений для edit/react/...
    uv run scripts/peer.py react +77007654321 42 👍
    uv run scripts/peer.py group "Тест" +77007654321

Собеседник-бот (если второго номера нет): создать бота у @BotFather в тестовом аккаунте и
положить токен в peer.env как PEER_BOT_TOKEN=... Тогда команды идут от бота (или явно --bot):
    uv run scripts/peer.py --bot login
    uv run scripts/peer.py --bot send <id чата> "привет"      # сначала /start боту из Reach
Боту недоступны: contact, group, supergroup, channel, read, chats, history. Писать первым он не может.
В группе, где бот админ, работают add / kick / admin / invite / topic.

Без --phone берётся PEER_PHONE из окружения, иначе последний номер, с которым был login.
Адресат (<peer>) — +номер из контактов, @username, числовой id или `me` (Избранное).
"""

import argparse
import asyncio
import os
import sys
from pathlib import Path

from telethon import TelegramClient, functions, types
from telethon.errors import PhoneNumberUnoccupiedError, SessionPasswordNeededError

# Адреса тестовых DC (https://core.telegram.org/api/datacenter)
TEST_DCS = {1: "149.154.175.10", 2: "149.154.167.40", 3: "149.154.175.117"}
STATE_DIR = Path.home() / ".local" / "share" / "reach-peer"
LAST_PHONE = STATE_DIR / "last_phone"
ENV_FILE = Path.home() / ".config" / "reach" / "peer.env"


def fail(msg: str) -> None:
    print(f"Ошибка: {msg}", file=sys.stderr)
    sys.exit(1)


def resolve_phone(arg: str | None) -> str:
    phone = arg or os.environ.get("PEER_PHONE") or (LAST_PHONE.read_text().strip() if LAST_PHONE.exists() else "")
    phone = phone.strip().lstrip("+").replace(" ", "").replace("-", "")
    if not phone.isdigit():
        fail(f"нужен номер телефона (--phone или PEER_PHONE), получено '{phone}'")
    return phone


def load_env_file() -> None:
    """KEY=VALUE из ENV_FILE в окружение; уже заданные переменные не перетираются."""
    if not ENV_FILE.exists():
        return
    for line in ENV_FILE.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip().removeprefix("export ").strip(), value.strip().strip("'\""))


def make_client(phone: str) -> TelegramClient:
    load_env_file()
    api_id, api_hash = os.environ.get("APP_ID"), os.environ.get("APP_HASH")
    if not api_id or not api_hash:
        fail(f"задайте APP_ID и APP_HASH в окружении или в {ENV_FILE}")
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    client = TelegramClient(str(STATE_DIR / phone), int(api_id), api_hash)
    if not client.session.auth_key:
        # Первый запуск: стартуем с test DC 2, на нужный DC Telegram перенаправит сам (PHONE_MIGRATE)
        client.session.set_dc(2, TEST_DCS[2], 80)
    return client


async def login(client: TelegramClient, phone: str, code: str | None, first_name: str) -> None:
    await client.connect()
    if await client.is_user_authorized():
        LAST_PHONE.write_text(phone)
        print("Уже авторизован")
        return
    hash_file = STATE_DIR / f"{phone}.code_hash"
    if not code:
        sent = await client.send_code_request(phone)
        hash_file.write_text(sent.phone_code_hash)
        print(f"Код отправлен ({type(sent.type).__name__.removeprefix('SentCodeType')}). "
              f"Завершите вход: login --code <код>")
        return
    if not hash_file.exists():
        fail("сначала запросите код: login без --code")
    code_hash = hash_file.read_text().strip()
    try:
        await client.sign_in(phone, code, phone_code_hash=code_hash)
    except SessionPasswordNeededError:
        fail("на тестовом аккаунте включён облачный пароль — отключите его в тестовой сессии на iPhone")
    except PhoneNumberUnoccupiedError:
        await client.sign_up(code, first_name, phone_code_hash=code_hash)
    hash_file.unlink(missing_ok=True)
    LAST_PHONE.write_text(phone)
    me = await client.get_me()
    print(f"Вход выполнен: id={me.id} name={me.first_name}")


async def peer(client: TelegramClient, ref: str):
    if ref == "me":
        return "me"
    if ref.startswith("+"):
        return await client.get_entity(ref)
    if ref.lstrip("-").isdigit():
        return await client.get_entity(int(ref))
    return await client.get_entity(ref)


def describe(m) -> str:
    kind = type(m.media).__name__.removeprefix("MessageMedia") if m.media else ""
    text = (m.message or "").replace("\n", " ")[:60]
    who = getattr(m.sender, "first_name", None) or getattr(m.sender, "title", "") if m.sender else ""
    return f"{m.id:>6}  {m.date:%Y-%m-%d %H:%M:%S}  {who:<12} {('[' + kind + '] ') if kind else ''}{text}"


async def run(args) -> None:
    load_env_file()
    if args.bot or (not args.phone and not os.environ.get("PEER_PHONE") and os.environ.get("PEER_BOT_TOKEN")):
        await run_bot(args)
        return
    phone = resolve_phone(args.phone)
    client = make_client(phone)
    if args.cmd == "login":
        await login(client, phone, args.code, args.name)
        await client.disconnect()
        return
    await client.connect()
    if not await client.is_user_authorized():
        fail(f"номер {phone} не авторизован — сначала `login`")
    try:
        await dispatch(client, args)
    finally:
        await client.disconnect()


async def run_bot(args) -> None:
    """Собеседник-бот: вход по токену @BotFather с test DC, номер не нужен."""
    token = os.environ.get("PEER_BOT_TOKEN")
    if not token:
        fail(f"задайте PEER_BOT_TOKEN в окружении или в {ENV_FILE}")
    if args.cmd in BOT_UNSUPPORTED:
        fail(f"`{args.cmd}` боту недоступна (боты не создают чаты и не импортируют контакты)")
    client = make_client("bot-" + token.split(":", 1)[0])
    await client.start(bot_token=token)
    try:
        if args.cmd == "login":
            me = await client.get_me()
            print(f"Бот авторизован: @{me.username} id={me.id}")
        else:
            await dispatch(client, args)
    finally:
        await client.disconnect()


# Методы, которые Telegram не даёт ботам
BOT_UNSUPPORTED = {"contact", "group", "supergroup", "channel", "read", "chats", "history"}


async def dispatch(client: TelegramClient, a) -> None:
    c = a.cmd
    if c == "me":
        me = await client.get_me()
        print(f"id={me.id} name={me.first_name} phone={me.phone}")
    elif c == "contact":
        r = await client(functions.contacts.ImportContactsRequest([
            types.InputPhoneContact(client_id=0, phone=a.phone_number, first_name=a.name, last_name="")
        ]))
        print(f"Импортировано: {[u.id for u in r.users] or 'никого (номер не зарегистрирован?)'}")
    elif c == "send":
        m = await client.send_message(await peer(client, a.peer), a.text, reply_to=a.reply_to)
        print(f"id={m.id}")
    elif c in ("photo", "video", "file", "audio", "voice", "round"):
        m = await client.send_file(
            await peer(client, a.peer), a.path, caption=a.caption or "",
            force_document=c == "file", voice_note=c == "voice", video_note=c == "round",
            supports_streaming=c == "video", reply_to=a.reply_to,
        )
        print(f"id={m.id}")
    elif c == "sticker":
        s = await client(functions.messages.GetStickerSetRequest(types.InputStickerSetShortName(a.set), hash=0))
        m = await client.send_file(await peer(client, a.peer), s.documents[a.index])
        print(f"id={m.id}")
    elif c == "edit":
        await client.edit_message(await peer(client, a.peer), a.id, a.text)
        print("ok")
    elif c == "delete":
        await client.delete_messages(await peer(client, a.peer), a.ids, revoke=True)
        print("ok")
    elif c == "react":
        reaction = [types.ReactionEmoji(emoticon=a.emoji)] if a.emoji else []
        await client(functions.messages.SendReactionRequest(peer=await peer(client, a.peer), msg_id=a.id, reaction=reaction))
        print("ok")
    elif c == "forward":
        await client.forward_messages(await peer(client, a.to), a.ids, await peer(client, a.source))
        print("ok")
    elif c == "pin":
        await client.pin_message(await peer(client, a.peer), a.id, notify=False)
        print("ok")
    elif c == "read":
        await client.send_read_acknowledge(await peer(client, a.peer))
        print("ok")
    elif c == "typing":
        async with client.action(await peer(client, a.peer), a.action):
            await asyncio.sleep(a.seconds)
    elif c == "config":
        from telethon.tl.functions.help import GetConfigRequest
        cfg = await client(GetConfigRequest())
        print(f"edit_time_limit={cfg.edit_time_limit}s revoke_pm_time_limit={cfg.revoke_pm_time_limit}s")
    elif c == "history":
        async for m in client.iter_messages(await peer(client, a.peer), limit=a.n):
            print(describe(m))
    elif c == "chats":
        async for d in client.iter_dialogs(limit=a.n):
            print(f"{d.id:>16}  {d.name}")
    elif c == "group":
        users = [await peer(client, u) for u in a.users]
        r = await client(functions.messages.CreateChatRequest(users=users, title=a.title))
        chat = r.updates.chats[0] if hasattr(r, "updates") else r.chats[0]
        print(f"id=-{chat.id}")
    elif c in ("supergroup", "channel"):
        r = await client(functions.channels.CreateChannelRequest(
            title=a.title, about="", megagroup=c == "supergroup", forum=getattr(a, "forum", False)))
        ch = r.chats[0]
        if a.users:
            await client(functions.channels.InviteToChannelRequest(ch, [await peer(client, u) for u in a.users]))
        print(f"id=-100{ch.id}")
    elif c == "add":
        chat = await peer(client, a.chat)
        for u in a.users:
            if isinstance(chat, types.Channel):
                await client(functions.channels.InviteToChannelRequest(chat, [await peer(client, u)]))
            else:
                await client(functions.messages.AddChatUserRequest(chat.id, await peer(client, u), fwd_limit=50))
        print("ok")
    elif c == "kick":
        await client.kick_participant(await peer(client, a.chat), await peer(client, a.user))
        print("ok")
    elif c == "admin":
        await client.edit_admin(await peer(client, a.chat), await peer(client, a.user), is_admin=True, title=a.title)
        print("ok")
    elif c == "invite":
        r = await client(functions.messages.ExportChatInviteRequest(peer=await peer(client, a.chat), title=a.title))
        print(r.link)
    elif c == "topic":
        r = await client(functions.channels.CreateForumTopicRequest(channel=await peer(client, a.chat), title=a.title))
        print("ok")
    else:
        fail(f"неизвестная команда {c}")


def main() -> None:
    p = argparse.ArgumentParser(description="Собеседник на test DC для проверок Reach")
    p.add_argument("--bot", action="store_true", help="действовать от бота (PEER_BOT_TOKEN)")
    p.add_argument("--phone", help="номер, зарегистрированный на test DC (иначе PEER_PHONE / последний login)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("login", help="без --code — запросить код, с --code — завершить вход")
    s.add_argument("--code", help="код из тестовой сессии на iPhone")
    s.add_argument("--name", default="Peer", help="имя, если номер ещё не зарегистрирован на test DC")
    sub.add_parser("me", help="кто я")
    s = sub.add_parser("contact", help="добавить номер в контакты, чтобы адресовать по нему")
    s.add_argument("phone_number")
    s.add_argument("name")

    s = sub.add_parser("send", help="текст")
    s.add_argument("peer"); s.add_argument("text"); s.add_argument("--reply-to", type=int)
    for kind, helptext in [("photo", "фото"), ("video", "видео"), ("file", "файл документом"),
                           ("audio", "музыкальный трек (mp3: плеер, а не документ)"),
                           ("voice", "голосовое (ogg/opus)"), ("round", "видеокружок (квадратное mp4)")]:
        s = sub.add_parser(kind, help=helptext)
        s.add_argument("peer"); s.add_argument("path")
        s.add_argument("--caption"); s.add_argument("--reply-to", type=int)
    s = sub.add_parser("sticker", help="стикер из набора по short name")
    s.add_argument("peer"); s.add_argument("set"); s.add_argument("index", type=int, nargs="?", default=0)

    s = sub.add_parser("edit"); s.add_argument("peer"); s.add_argument("id", type=int); s.add_argument("text")
    s = sub.add_parser("delete"); s.add_argument("peer"); s.add_argument("ids", type=int, nargs="+")
    s = sub.add_parser("react", help="реакция; без emoji — снять")
    s.add_argument("peer"); s.add_argument("id", type=int); s.add_argument("emoji", nargs="?")
    s = sub.add_parser("forward", help="переслать ids из source в to")
    s.add_argument("source"); s.add_argument("to"); s.add_argument("ids", type=int, nargs="+")
    s = sub.add_parser("pin"); s.add_argument("peer"); s.add_argument("id", type=int)
    s = sub.add_parser("read", help="отметить чат прочитанным (галочки у агента)"); s.add_argument("peer")
    s = sub.add_parser("typing", help="статус «печатает» на N секунд")
    s.add_argument("peer"); s.add_argument("--action", default="typing"); s.add_argument("--seconds", type=float, default=5)
    s = sub.add_parser("history"); s.add_argument("peer"); s.add_argument("-n", type=int, default=10)
    sub.add_parser("config", help="лимиты сервера: время на правку и удаление")
    s = sub.add_parser("chats"); s.add_argument("-n", type=int, default=20)

    s = sub.add_parser("group", help="обычная группа"); s.add_argument("title"); s.add_argument("users", nargs="+")
    s = sub.add_parser("supergroup"); s.add_argument("title"); s.add_argument("users", nargs="*")
    s.add_argument("--forum", action="store_true", help="с темами")
    s = sub.add_parser("channel"); s.add_argument("title"); s.add_argument("users", nargs="*")
    s = sub.add_parser("add", help="добавить участников"); s.add_argument("chat"); s.add_argument("users", nargs="+")
    s = sub.add_parser("kick"); s.add_argument("chat"); s.add_argument("user")
    s = sub.add_parser("admin"); s.add_argument("chat"); s.add_argument("user"); s.add_argument("--title")
    s = sub.add_parser("invite", help="новая ссылка-приглашение"); s.add_argument("chat"); s.add_argument("--title")
    s = sub.add_parser("topic", help="тема в форуме"); s.add_argument("chat"); s.add_argument("title")

    asyncio.run(run(p.parse_args()))


if __name__ == "__main__":
    main()
