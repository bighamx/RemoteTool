import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('android_ci',Path(__file__).resolve().parents[1]/'tools/android_ci.py')
ci=importlib.util.module_from_spec(spec);spec.loader.exec_module(ci)


class AndroidVersionTests(unittest.TestCase):
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


if __name__=='__main__': unittest.main()
