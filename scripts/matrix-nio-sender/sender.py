import asyncio
import json
import os
import sys
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from aiohttp import web
import aiohttp
from nio import (
    AsyncClient,
    ClientConfig,
    EnableEncryptionBuilder,
    KeysQueryError,
    LoginResponse,
    RoomCreateResponse,
    RoomGetEventResponse,
    RoomPutStateResponse,
    RoomSendResponse,
    ShareGroupSessionResponse,
)


HOMESERVER = os.environ["MATRIX_HOMESERVER"]
ALICE_USER = os.environ["KAILINK_USER"]
ALICE_PASSWORD = os.environ["KAILINK_PASSWORD"]
SENDER_USER = os.environ["SENDER_USER"]
SENDER_PASSWORD = os.environ["SENDER_PASSWORD"]
STATE = Path(os.environ["STATE_DIR"])
PORT = int(os.environ.get("HARNESS_PORT", "8088"))
SAFE_DEVICE_ID = re.compile(r"^[A-Za-z0-9._-]+$")


def fail(message: str) -> None:
    print(f"sender: ERROR: {message}", flush=True)
    raise RuntimeError(message)


async def register_if_needed(username: str, password: str) -> None:
    # Conduit's dummy UIA flow is a small provisioning seam not exposed by
    # nio's current register() signature. The actual E2EE client/login/send
    # path remains matrix-nio; this request only creates its throwaway account.
    async with aiohttp.ClientSession() as session:
        async with session.post(
            f"{HOMESERVER}/_matrix/client/v3/register",
            json={"username": username, "password": password, "auth": {"type": "m.login.dummy"}},
        ) as response:
            payload = await response.text()
            if response.status not in (200, 201) and "M_USER_IN_USE" not in payload:
                fail(f"registration failed for {username}: HTTP {response.status}: {payload[:300]}")


async def login(username: str, password: str, store_name: str) -> AsyncClient:
    config = ClientConfig(encryption_enabled=True, store_sync_tokens=True)
    store = STATE / store_name
    store.mkdir(parents=True, exist_ok=True)
    client = AsyncClient(HOMESERVER, user=username, store_path=str(store), config=config)
    response = await client.login(password=password, device_name="KaiLink Stage 1 sender")
    if not isinstance(response, LoginResponse):
        await client.close()
        fail(f"login failed for {username}: {response}")
    if client.olm is None:
        await client.close()
        fail("matrix-nio did not initialize the vodozemac E2EE backend")
    print(f"sender: logged in {username} as {client.user_id}", flush=True)
    return client


async def sync(client: AsyncClient, label: str) -> None:
    response = await client.sync(timeout=5000, full_state=True)
    if getattr(response, "next_batch", None) is None:
        fail(f"{label} sync failed: {response}")


async def wait_for_recipient_devices(
    client: AsyncClient, room_id: str, user_id: str, expected_device: str
) -> None:
    """Require the joined room's current device list before encrypting."""
    deadline = asyncio.get_running_loop().time() + 60
    attempt = 0
    last_error = "no device keys returned"
    print(
        f"sender: recipient readiness room={room_id} recipient={user_id} "
        f"expected_device={expected_device}",
        flush=True,
    )
    while asyncio.get_running_loop().time() < deadline:
        attempt += 1
        timestamp = datetime.now(timezone.utc).isoformat()
        queried_devices = {}
        try:
            await sync(client, f"recipient device discovery {attempt}")
            # Refresh the room membership through nio so it marks the joined
            # users for its supported keys_query() request.
            await client.joined_members(room_id)
            client.olm.update_tracked_users(client.rooms[room_id])
            # nio removes users from this set after a successful query. Add the
            # joined recipient back so a late device-key upload is re-queried.
            client.users_for_key_query.add(user_id)
            should_query = client.should_query_keys
            if should_query:
                print(
                    f"sender: device query attempt={attempt} timestamp={timestamp} "
                    f"recipient={user_id} should_query_keys=true query_performed=true",
                    flush=True,
                )
                response = await client.keys_query()
                if isinstance(response, KeysQueryError):
                    last_error = f"key query failed: {response}"
                    print(
                        f"sender: device query result attempt={attempt} timestamp={datetime.now(timezone.utc).isoformat()} "
                        f"recipient={user_id} query_performed=true error={last_error}",
                        flush=True,
                    )
                else:
                    queried_devices = response.device_keys.get(user_id, {})
                    queried_ids = sorted(queried_devices)
                    print(
                        f"sender: device query result attempt={attempt} timestamp={datetime.now(timezone.utc).isoformat()} "
                        f"recipient={user_id} queried_count={len(queried_ids)} queried_ids={queried_ids} "
                        f"query_performed=true",
                        flush=True,
                    )
            else:
                print(
                    f"sender: device query result attempt={attempt} timestamp={datetime.now(timezone.utc).isoformat()} "
                    f"recipient={user_id} queried_count=0 queried_ids=[] query_performed=false "
                    "reason=no_key_query_required",
                    flush=True,
                )
            room_devices = client.room_devices(room_id).get(user_id, {})
            devices = {
                device.device_id: device
                for device in client.device_store.active_user_devices(user_id)
            }
            device_ids = sorted(devices)
            print(
                f"sender: room device result attempt={attempt} timestamp={datetime.now(timezone.utc).isoformat()} "
                f"recipient={user_id} count={len(device_ids)} device_ids={device_ids} "
                f"room_count={len(room_devices)} room_device_ids={sorted(room_devices)} "
                f"query_performed={should_query}",
                flush=True,
            )
            if expected_device not in devices:
                last_error = f"expected device {expected_device} absent from queried device list"
                print(
                    f"sender: expected device not ready attempt={attempt} "
                    f"expected_device={expected_device}",
                    flush=True,
                )
                devices = {}
        except Exception as exc:
            last_error = f"device readiness attempt failed: {exc}"
            print(
                f"sender: device query result attempt={attempt} timestamp={datetime.now(timezone.utc).isoformat()} "
                f"recipient={user_id} query_performed=false error={last_error}",
                flush=True,
            )
            devices = {}
        if devices:
            print(
                f"sender: recipient devices ready for {user_id} ({len(devices)} devices) "
                f"expected_device={expected_device}",
                flush=True,
            )
            return
        await asyncio.sleep(2)
    fail(f"no device keys available for joined recipient {user_id} after 60 seconds ({last_error})")


async def join_invited_room(client: AsyncClient, room_id: str) -> None:
    # Conduit rejects nio's empty-body POST /join request with M_BAD_JSON;
    # send the equivalent Matrix request with the legal empty JSON object.
    async with aiohttp.ClientSession() as session:
        async with session.post(
            f"{HOMESERVER}/_matrix/client/v3/join/{room_id}",
            params={"access_token": client.access_token},
            json={},
        ) as response:
            payload = await response.json(content_type=None)
            if response.status not in (200, 201) or payload.get("room_id") != room_id:
                fail(f"recipient join failed for {room_id}: HTTP {response.status}: {payload}")


async def verify_joined_membership(client: AsyncClient, room_id: str, user_id: str) -> None:
    response = await client.joined_members(room_id)
    members = getattr(response, "members", [])
    # matrix-nio 0.26.0 returns RoomMember objects here; sorting them is a
    # TypeError, so order the stable user IDs instead.
    member_ids = sorted(member.user_id for member in members)
    async with aiohttp.ClientSession() as session:
        async with session.get(
            f"{HOMESERVER}/_matrix/client/v3/rooms/{room_id}/state/m.room.member/{user_id}",
            params={"access_token": client.access_token},
        ) as membership_response:
            membership = await membership_response.json(content_type=None)
            membership_value = membership.get("membership")
    print(
        f"sender: joined membership room={room_id} recipient={user_id} "
        f"membership={membership_value} member_count={len(member_ids)} member_ids={member_ids}",
        flush=True,
    )
    if membership_value != "join" or user_id not in member_ids:
        fail(f"recipient membership is not joined for {user_id}")


async def verify_encryption_state(
    client: AsyncClient, room_id: str, expected_content: dict
) -> None:
    await sync(client, "sender after encryption state")
    room = client.rooms.get(room_id)
    if room is None or not room.encrypted:
        fail(f"room {room_id} is not encrypted after state sync")

    async with aiohttp.ClientSession() as session:
        async with session.get(
            f"{HOMESERVER}/_matrix/client/v3/rooms/{room_id}/state/m.room.encryption/",
            params={"access_token": client.access_token},
        ) as response:
            state_content = await response.json(content_type=None)
            if response.status != 200:
                fail(
                    f"encryption state lookup failed: HTTP {response.status}: "
                    f"{state_content}"
                )

    if (
        not isinstance(state_content, dict)
        or state_content != expected_content
        or state_content.get("algorithm") != "m.megolm.v1.aes-sha2"
    ):
        fail(f"encryption state lookup returned unexpected content: {state_content}")
    print(f"sender: encryption state verified room={room_id}", flush=True)


async def verify_wire_event(
    client: AsyncClient, room_id: str, event_id: str
) -> tuple[str, list[str]]:
    """Verify the event source after a recipient sync, without exposing its body."""
    await sync(client, "recipient after encrypted message")
    response = await client.room_get_event(room_id, event_id)
    if not isinstance(response, RoomGetEventResponse):
        fail(f"wire event lookup failed: {response}")

    event = response.event
    event_type = event.source.get("type")
    content = event.source.get("content")
    content_keys = sorted(content) if isinstance(content, dict) else []
    print(
        f"sender: wire event type={event_type} content_keys={content_keys}",
        flush=True,
    )
    if event_type != "m.room.encrypted" or not isinstance(content, dict):
        fail(f"wire event was not encrypted: type={event_type}")
    expected_keys = {"algorithm", "ciphertext", "session_id"}
    if not expected_keys.issubset(content):
        fail(f"wire encrypted content keys mismatch: {content_keys}")
    return event_type, content_keys


async def send_stage_one(body: str, expected_device: str) -> dict[str, object]:
    await register_if_needed(SENDER_USER, SENDER_PASSWORD)
    sender = await login(SENDER_USER, SENDER_PASSWORD, "sender")
    alice = await login(ALICE_USER, ALICE_PASSWORD, "recipient")
    try:
        room_response = await sender.room_create(
            name=f"KaiLink Stage 1 {body[-12:]}",
            invite=[alice.user_id],
            is_direct=False,
        )
        if not isinstance(room_response, RoomCreateResponse):
            fail(f"room creation failed: {room_response}")
        room_id = room_response.room_id
        print(f"sender: encrypted room created {room_id}", flush=True)
        await sync(sender, "sender after room creation")
        encryption = EnableEncryptionBuilder().as_dict()
        encryption_response = await sender.room_put_state(
            room_id,
            encryption["type"],
            encryption["content"],
            state_key="",
        )
        if not isinstance(encryption_response, RoomPutStateResponse):
            fail(f"encryption state failed: {encryption_response}")
        await verify_encryption_state(sender, room_id, encryption["content"])

        # The app account is invited above. Joining with a second device for
        # that same account makes the join boundary deterministic while the
        # KaiLink process remains the recipient that must decrypt/render.
        await sync(alice, "recipient invite")
        await join_invited_room(alice, room_id)
        await verify_joined_membership(sender, room_id, alice.user_id)
        await wait_for_recipient_devices(sender, room_id, alice.user_id, expected_device)
        for _ in range(4):
            await sync(sender, "sender key exchange")
            await sync(alice, "recipient key exchange")

        share_response = await sender.share_group_session(
            room_id,
            ignore_unverified_devices=True,
        )
        if not isinstance(share_response, ShareGroupSessionResponse):
            fail(f"Megolm session sharing failed: {share_response}")

        encrypted_type, encrypted_content = sender.encrypt(
            room_id,
            "m.room.message",
            {"msgtype": "m.text", "body": body},
        )
        message_response = await sender.room_send(
            room_id,
            encrypted_type,
            encrypted_content,
            ignore_unverified_devices=True,
        )
        if not isinstance(message_response, RoomSendResponse):
            fail(f"encrypted message failed: {message_response}")
        wire_type, wire_content_keys = await verify_wire_event(
            alice, room_id, message_response.event_id
        )
        print(f"sender: encrypted message sent event={message_response.event_id}", flush=True)
        return {
            "room_id": room_id,
            "event_id": message_response.event_id,
            "wire_type": wire_type,
            "wire_content_keys": wire_content_keys,
        }
    finally:
        await alice.close()
        await sender.close()


async def ready(_: web.Request) -> web.Response:
    return web.json_response({"ready": True})


async def trigger(request: web.Request) -> web.Response:
    try:
        payload = await request.json()
        body = payload.get("body", "")
        expected_device = payload.get("expected_device", "")
        if not body or len(body) > 200:
            raise ValueError("body must be a non-empty short string")
        if not isinstance(expected_device, str) or not SAFE_DEVICE_ID.fullmatch(expected_device):
            raise ValueError("expected_device must be a safe Matrix device identifier")
        print(f"sender: request received expected_device={expected_device}", flush=True)
        result = await send_stage_one(body, expected_device)
        return web.json_response({"ok": True, **result})
    except Exception as exc:
        print(f"sender: ERROR: {exc}", flush=True)
        return web.json_response({"ok": False, "error": str(exc)}, status=500)


async def main() -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    app = web.Application()
    app.router.add_get("/ready", ready)
    app.router.add_post("/send", trigger)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", PORT)
    await site.start()
    print(f"sender: ready on {PORT} homeserver={HOMESERVER}", flush=True)
    while True:
        await asyncio.sleep(3600)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:
        print(f"sender: fatal: {exc}", file=sys.stderr, flush=True)
        raise
