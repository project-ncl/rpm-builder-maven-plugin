#!/usr/bin/python3

from specfile import Specfile
import argparse
import os

parser = argparse.ArgumentParser(
    prog='generateChangelog',
    description='Generate changelog entry for wrapper RPM builds')
parser.add_argument('originalVersion')
parser.add_argument('rhVersion')
parser.add_argument('serial')
args = parser.parse_args()

spec = [x for x in os.listdir(os.getcwd()) if x.endswith('.spec')]
if len(spec) != 1:
    print("ERROR: Found multiple spec files " + str(spec))
    exit(1)

print ("Found spec file " +  spec[0])
specfile = Specfile(spec[0])
specfile.add_changelog_entry('- New release', evr=args.originalVersion + "-" + args.serial + args.rhVersion + ".1", author="project-ncl@redhat.com")
specfile.save()
