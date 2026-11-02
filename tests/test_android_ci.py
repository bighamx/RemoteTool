import importlib.util
from pathlib import Path
import unittest
import json
import tempfile
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('android_ci',Path(__file__).resolve().parents[1]/'tools/android_ci.py')
ci=importlib.util.module_from_spec(spec);spec.loader.exec_module(ci)


class AndroidVersionTests(unittest.TestCase):
    def test_stable_release_does_not_require_or_publish_server_zip(self):
        info={'commit':'a'*40,'channel':'stable','versionCode':1711}
        with tempfile.TemporaryDirectory() as folder:
            out=Path(folder)
            (out/'build.json').write_text(json.dumps(info))
            for flavor,(_,manifest) in ci.EDITIONS.items():
                apk=f'{flavor}.apk';(out/apk).write_bytes(b'fixture')
                (out/manifest).write_text(json.dumps({'apkAsset':apk}))
            with patch.dict(ci.os.environ,{'GITHUB_REF_NAME':'android-v0.3.147-build1711'}),patch.object(ci,'plan',return_value=info),patch.object(ci,'release_info',return_value=None),patch.object(ci,'run',return_value='a'*40) as command,patch.object(ci,'verify_assets') as verify:
                ci.publish(out,'stable')
                files=verify.call_args.args[1]
                self.assertEqual(4,len(files))
                self.assertNotIn('RemoteTool.zip',[p.name for p in files])
                self.assertTrue(any(args.args[:3]==('gh','release','edit') and '--draft=false' in args.args for args in command.call_args_list))

    def test_channel_switch_and_next_commit_are_monotonic(self):
        count=350
        def git(*args): return 'a'*40 if args[1]=='rev-parse' else str(count)
        with patch.object(ci,'run',side_effect=git):
            dev=ci.plan('development');stable=ci.plan('stable')
            self.assertGreater(dev['versionCode'],148)
            self.assertEqual(dev['versionCode']+1,stable['versionCode'])
            count+=1
            self.assertGreater(ci.plan('development')['versionCode'],stable['versionCode'])
            self.assertEqual('a'*40,stable['commit'])

    def test_app_and_phone_feeds_are_separate(self):
        self.assertNotEqual(ci.EDITIONS['full'][0],ci.EDITIONS['phone'][0])
        self.assertNotEqual(ci.EDITIONS['full'][1],ci.EDITIONS['phone'][1])

    def test_development_cleanup_keeps_manifest_targets_not_upload_order(self):
        current=['RemoteTool-0.3.146-dev.abc123-1706.apk','RemoteTool-PhoneControl-0.2.18-dev.abc123-1706.apk']
        old=['RemoteTool-0.3.146-dev.def456-1704.apk','RemoteTool-PhoneControl-0.2.18-dev.def456-1704.apk']
        names=current+old+['remotetool-update.json','phonecontrol-update.json','RemoteTool.zip','other.apk']
        with patch.object(ci,'release_info',return_value={'assets':[{'name':name,'state':'uploaded'} for name in names]}),patch.object(ci,'run') as run:
            ci.prune_development_apks('android-development',current)
            self.assertEqual([('gh','release','delete-asset','android-development',name,'--yes') for name in old],[call.args for call in run.call_args_list])

    def test_cleanup_refuses_stable_tags_and_incomplete_uploads(self):
        current=['RemoteTool-0.3.146-dev.abc123-1706.apk','RemoteTool-PhoneControl-0.2.18-dev.abc123-1706.apk']
        with patch.object(ci,'run') as run:
            with self.assertRaises(AssertionError):ci.prune_development_apks('android-v0.3.146',current)
            with patch.object(ci,'release_info',return_value={'assets':[{'name':current[0],'state':'uploaded'}]}):
                with self.assertRaises(AssertionError):ci.prune_development_apks('android-development',current)
            with patch.object(ci,'release_info',return_value={'assets':[{'name':name,'state':'new' if name==current[0] else 'uploaded'} for name in current]}):
                with self.assertRaises(AssertionError):ci.prune_development_apks('android-development',current)
            run.assert_not_called()


if __name__=='__main__': unittest.main()
