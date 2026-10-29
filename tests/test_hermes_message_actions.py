import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("actions", Path(__file__).parents[1] / "tools/HermesManagement/hermes_message_actions.py")
actions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(actions)


class FakeDB:
    def __init__(self):
        self.sessions = {"source": {"id": "source", "title": "Source", "model": "test", "cwd": "example", "model_config": {"custom": "keep"}}}
        self.rows = [{"id": 10, "role": "user", "content": "old"}, {"id": 11, "role": "assistant", "content": "answer"}, {"id": 12, "role": "user", "content": "later"}]
        self.rewind = None
    def get_session(self, sid): return copy.deepcopy(self.sessions.get(sid))
    def resolve_resume_session_id(self, sid): return sid
    def get_messages(self, sid): return copy.deepcopy(self.rows)
    def get_active_message_ids(self, sid): return [row["id"] for row in self.rows]
    def rewind_to_message(self, sid, mid, **kw): self.rewind = (sid, mid, kw); return {"rewound_count": 3}
    def create_session(self, sid, source, **kw): self.sessions[sid] = {"id": sid, **kw}
    def replace_messages(self, sid, rows): self.copied = rows
    def set_session_title(self, sid, title): self.sessions[sid]["title"] = title
    def delete_session(self, sid): self.sessions.pop(sid)


class ActionsTests(unittest.TestCase):
    def test_append_after_cas_snapshot_is_rejected_even_when_latest_user_is_unchanged(self):
        class RacingDB(FakeDB):
            def get_messages(self, sid):
                self.rows.append({"id": 13, "role": "assistant", "content": "new reply"})
                return super().get_messages(sid)
            def rewind_to_message(self, sid, mid, **kw):
                if kw["expected_active_ids"] != [row["id"] for row in self.rows]:
                    raise RuntimeError("active transcript changed")
                return super().rewind_to_message(sid, mid, **kw)
        db = RacingDB()
        with patch.object(actions, "_native_rewind_target", return_value=(False, "old")), self.assertRaises(RuntimeError):
            actions.message_action(db, {"session_id": "source", "operation": "rewind", "message_id": 10, "expected_latest_user_id": 12})
        self.assertIsNone(db.rewind)

    def test_rewind_uses_canonical_native_guarded_transaction(self):
        db = FakeDB()
        with patch.object(actions, "_native_rewind_target", return_value=(False, "old")):
            self.assertTrue(actions.message_action(db, {"session_id": "source", "operation": "rewind", "message_id": 10, "expected_latest_user_id": 12})["rewound"])
        self.assertEqual(db.rewind[2]["expected_active_ids"], [10, 11, 12])
        self.assertEqual(db.rewind[2]["expected_target_content"], "old")
        self.assertFalse(db.rewind[2]["preserve_compaction_handoff"])

    def test_composite_carrier_preserves_only_its_native_handoff_and_live_payload(self):
        db = FakeDB()
        with patch.object(actions, "_native_rewind_target", return_value=(True, "live prompt")):
            actions.message_action(db, {"session_id": "source", "operation": "rewind", "message_id": 10, "expected_latest_user_id": 12})
        self.assertTrue(db.rewind[2]["preserve_compaction_handoff"])
        self.assertEqual(db.rewind[2]["expected_target_content"], "live prompt")

    def test_changed_transcript_and_assistant_edit_never_rewind(self):
        for mid, expected in [(10, 10), (11, 12), (999, 12)]:
            db = FakeDB()
            with self.assertRaises(ValueError): actions.message_action(db, {"session_id": "source", "operation": "rewind", "message_id": mid, "expected_latest_user_id": expected})
            self.assertIsNone(db.rewind)

    def test_fork_keeps_only_prefix_and_does_not_modify_or_end_source(self):
        db = FakeDB(); original = copy.deepcopy(db.sessions["source"])
        body = {"session_id": "source", "operation": "fork", "message_id": 11, "fork_id": "mobile_fork_" + "a" * 32}
        result = actions.message_action(db, body)
        self.assertEqual([r["content"] for r in db.copied], ["old", "answer"])
        self.assertNotIn("id", db.copied[0])
        self.assertEqual(original, db.sessions["source"])
        self.assertEqual(db.sessions[result["session"]["id"]]["model_config"]["custom"], "keep")
        self.assertNotIn("model_config", result["session"])
        self.assertNotIn("system_prompt", result["session"])
        self.assertEqual(actions.message_action(db, body)["session"]["id"], result["session"]["id"])
        self.assertEqual(len(db.sessions), 2)

    def test_fork_accepts_the_web_prefix_as_well(self):
        db = FakeDB()
        web = {"session_id": "source", "operation": "fork", "message_id": 11, "fork_id": "web_fork_" + "b" * 32}
        self.assertEqual(actions.message_action(db, web)["session"]["id"], web["fork_id"])
        for bad in ("web_fork_" + "B" * 32, "fork_" + "b" * 32, "web_fork_" + "b" * 31):
            with self.assertRaises(ValueError):
                actions.message_action(db, {**web, "fork_id": bad})


class NativeActionsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="remotetool-rewind-")
        self.addCleanup(self.temp.cleanup)
        environment = patch.dict(os.environ, {"HERMES_HOME": self.temp.name, "HERMES_DISABLE_LAZY_INSTALLS": "1"})
        environment.start()
        self.addCleanup(environment.stop)
        try:
            from hermes_state import SessionDB
        except ImportError as error:
            self.skipTest("Hermes runtime required: " + str(error))
        self.db = SessionDB(db_path=Path(self.temp.name) / "fixture.db")
        self.addCleanup(self.db.close)
        self.db.create_session("source", "api_server")

    def request(self, mid):
        return {"session_id": "source", "operation": "rewind", "message_id": mid, "expected_latest_user_id": mid}

    def test_gateway_user_rewinds_through_both_native_guards_without_rewriting_tags(self):
        metadata = {"gateway_input_owner": "fixture-input-owner"}
        mid = self.db.append_message("source", "user", "real user request", display_kind="internal_notification", display_metadata=metadata)
        self.db.append_message("source", "assistant", "answer")
        self.assertTrue(actions.message_action(self.db, self.request(mid))["rewound"])
        self.assertEqual(self.db.get_active_message_ids("source"), [])
        archived = next(row for row in self.db.get_messages("source", include_inactive=True) if row["id"] == mid)
        self.assertEqual(archived["display_kind"], "internal_notification")
        self.assertEqual(archived["display_metadata"], metadata)
        self.assertNotIn("_split_rewind_target", self.db.__dict__)

    def test_synthetic_notifications_without_a_real_owner_never_rewind(self):
        for metadata in ({}, {"gateway_input_owner": ""}, {"gateway_input_owner": 123}, {"gateway_input_owner": "   "}):
            with self.subTest(metadata=metadata):
                mid = self.db.append_message("source", "user", "synthetic status", display_kind="internal_notification", display_metadata=metadata)
                before = self.db.get_active_message_ids("source")
                with self.assertRaises(ValueError):
                    actions.message_action(self.db, self.request(mid))
                self.assertEqual(self.db.get_active_message_ids("source"), before)

    def test_native_transaction_still_rejects_changed_active_set_and_restores_adapter(self):
        mid = self.db.append_message("source", "user", "real request", display_kind="internal_notification", display_metadata={"gateway_input_owner": "owner"})
        native_rewind = self.db.rewind_to_message
        def race(*args, **kwargs):
            self.db.append_message("source", "assistant", "concurrent answer")
            return native_rewind(*args, **kwargs)
        with patch.object(self.db, "rewind_to_message", side_effect=race), self.assertRaises(RuntimeError):
            actions.message_action(self.db, self.request(mid))
        self.assertEqual(len(self.db.get_active_message_ids("source")), 2)
        self.assertNotIn("_split_rewind_target", self.db.__dict__)

    def test_native_transaction_rechecks_owner_provenance_before_any_rewind(self):
        mid = self.db.append_message("source", "user", "real request", display_kind="internal_notification", display_metadata={"gateway_input_owner": "owner"})
        native_rewind = self.db.rewind_to_message
        def revoke_owner(*args, **kwargs):
            self.db._execute_write(lambda connection: connection.execute("UPDATE messages SET display_metadata='{}' WHERE id=?", (mid,)))
            return native_rewind(*args, **kwargs)
        with patch.object(self.db, "rewind_to_message", side_effect=revoke_owner), self.assertRaises(ValueError):
            actions.message_action(self.db, self.request(mid))
        self.assertEqual(self.db.get_active_message_ids("source"), [mid])
        self.assertNotIn("_split_rewind_target", self.db.__dict__)


if __name__ == "__main__": unittest.main()
