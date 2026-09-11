import ast
from pathlib import Path


SOURCE = Path(__file__).with_name("sender.py")


def test_stage_one_uses_explicit_matrix_nio_encryption_before_send():
    tree = ast.parse(SOURCE.read_text())
    send_stage_one = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "send_stage_one"
    )

    encrypt_calls = [
        node
        for node in ast.walk(send_stage_one)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "encrypt"
    ]
    assert encrypt_calls, "stage one must call matrix-nio's explicit encrypt API"

    plaintext_calls = [
        node
        for node in ast.walk(send_stage_one)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "room_send"
        and len(node.args) >= 2
        and isinstance(node.args[1], ast.Constant)
        and node.args[1].value == "m.room.message"
    ]
    assert not plaintext_calls, "stage one must not send m.room.message directly"

    encrypted_send = next(
        node
        for node in ast.walk(send_stage_one)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "room_send"
        and len(node.args) >= 3
        and isinstance(node.args[1], ast.Name)
        and isinstance(node.args[2], ast.Name)
    )
    assert encrypted_send.args[1].id == "encrypted_type"
    assert encrypted_send.args[2].id == "encrypted_content"


def test_stage_one_shares_group_session_before_encrypting_and_reports_wire_shape():
    tree = ast.parse(SOURCE.read_text())
    send_stage_one = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "send_stage_one"
    )
    calls = [
        node
        for node in ast.walk(send_stage_one)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
    ]
    share = next(node for node in calls if node.func.attr == "share_group_session")
    encrypt = next(node for node in calls if node.func.attr == "encrypt")
    assert share.lineno < encrypt.lineno, "Megolm session must be shared before encrypt"
    assert sum(node.func.attr == "room_send" for node in calls) == 1

    response_text = ast.unparse(send_stage_one)
    for field in ("event_id", "room_id", "wire_type", "wire_content_keys"):
        assert field in response_text, f"/send response must include {field}"


if __name__ == "__main__":
    test_stage_one_uses_explicit_matrix_nio_encryption_before_send()
