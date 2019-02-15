import datetime
import hashlib
import sys
import traceback
from concurrent.futures import ThreadPoolExecutor
import os

# allow py27 lint to pass
try:
    FileNotFoundError
except NameError:
    FileNotFoundError = IOError

import yaml
import click
from junit_xml import TestSuite, TestCase

hash_cache = {}


def getsha1(path):
    """ Generate sha1 sum of a file

    Uses a cache dict because multiple Packages files
    may reference the same package on disk.
    """
    try:
        return hash_cache[path]
    except KeyError:
        h = hashlib.sha1()
        # 2 MB in bytes
        chunk_size = 2**21
        with open(path, 'rb') as f:
            while True:
                chunk = f.read(chunk_size)
                if not chunk:
                    break
                h.update(chunk)
        sha = h.hexdigest()
        del(h)
        hash_cache[path] = sha
        return sha


def chk_pkg(pkg):
    disksha = getsha1('{rp}/{pp}'.format(
        rp=pkg['repo_path'], pp=pkg['Filename']))
    pkg['disksha'] = disksha
    return pkg


def print_stat(total, done, start, done_mb):
    elapsed = datetime.datetime.now() - start
    if elapsed.seconds > 0:
        print("{done}/{total} ({p:.2%}) "
              "time elapsed: {te} time left: {tl} "
              "{hps:.0f} hashes/s {mbps:.0f} MB/s"
              .format(done=done, total=total,
                      p=(done / float(total)),
                      te=elapsed,
                      tl=(elapsed / done) * (total - done),
                      hps=done / elapsed.seconds,
                      mbps=done_mb / elapsed.seconds))


def _verify_packages_file(packages, repo_path, workers,
                          should_write_hash_cache=False):
    # Based On
    # https://github.com/JeremyGrosser/repoman
    # /blob/master/repoman/repository.py#L59-L83

    # 1. Parse the packages file and create a list of dicts
    # one dict per package
    packages_path = packages.name
    print("Processing packages file: {pf}".format(pf=packages_path))
    pkglist = []
    packages = packages.read().split('\n\n')
    for pkg in packages:
        fields = []
        for field in pkg.split('\n'):
            if not field:
                continue
            if field[0].isalpha():
                fields.append(field.split(': ', 1))
            else:
                fields[-1][1] += field
        if not fields:
            continue
        pkg = dict(fields)
        pkg['repo_path'] = repo_path
        pkg['Distribution'] = packages_path.split('/')[-4]
        pkg['repo_name'] = packages_path.split('/')[-6]
        pkglist.append(pkg)

    # 2. Verify the SHA1 sum of every package entry from the packages file
    done = 0
    done_mb = 0
    # 1 MB in bytes
    mb = 2**20
    stat_interval = 1000
    total = len(pkglist)
    start = datetime.datetime.now()
    with ThreadPoolExecutor(max_workers=workers) as tpe:
        for pkg in tpe.map(chk_pkg, pkglist):
            done += 1
            done_mb += int(pkg['Size']) / mb
            sha = pkg['SHA1']
            # disksha is not a packages file field
            # it's added by chk_pkg_list
            disksha = pkg['disksha']
            name = pkg['Package']
            if sha != disksha:
                print("Sha Mismatch: {pn} disksha: {ds}, "
                      "packages sha: {ps}"
                      .format(ds=disksha, pn=name, ps=sha))
            else:
                # Print progress
                # because this takes a while.
                if done % stat_interval == 0:
                    print_stat(total, done, start, done_mb)
            sys.stdout.flush()
    print_stat(total, done, start, done_mb)
    if should_write_hash_cache:
        write_hash_cache()
    return pkglist


def find_packages_files(repo_path):
    for dirpath, dirs, files in os.walk(repo_path):
        try:
            if "Packages" in files:
                packages_file = '{dp}/Packages'.format(dp=dirpath)
                with open(packages_file) as pf:
                    yield pf
        except Exception as e:
            print("Exception while reading {dp}/Packages: {e}"
                  .format(dp=dirpath, e=e))


def write_hash_cache():
    hcp = click.get_current_context().obj['hash_cache_path']
    if hcp:
        with open(hcp, 'w') as hcf:
            hcf.write(yaml.dump(hash_cache, default_flow_style=False))


@click.group()
@click.option('--workers', help='number of checksumming workers',
              default=4, type=int)
@click.option("--repo-path",
              help="Usually the dir that contains the pool directory",
              required=True)
@click.option("--hash-cache", 'hcp',
              help="yaml file containing hash data, usually not "
              "required as hahses should be regenerated every time, "
              "but good for testing.")
@click.pass_context
def cli(ctx, workers, repo_path, hcp):
    ctx.obj = dict(workers=workers, repo_path=repo_path,
                   hash_cache_path=hcp)
    global hash_cache
    if (hcp):
        try:
            with open(hcp) as hcf:
                hash_cache = yaml.load(hcf.read())
                if hash_cache:
                    print("Read hash cache file {hcp} with {n} entries"
                          .format(hcp=hcp, n=len(hash_cache)))
                else:
                    print("Read empty cache file {hcp}".format(hcp=hcp))
                    hash_cache = {}
        except FileNotFoundError:
            print("Cache file {} not found, will be created."
                  .format(hcp))


@cli.command()
@click.option('--packages', help='Packages file path',
              required=True, type=click.File())
@click.pass_context
def verify_packages_file(ctx, packages):
    workers = ctx.obj['workers']
    repo_path = ctx.obj['repo_path']
    _verify_packages_file(packages, repo_path, workers,
                          should_write_hash_cache=True)


@cli.command()
@click.option('--yaml-report',
              help="Write yaml report file, "
                   "this lists only packages file entries "
                   "with mismatched hashes.")
@click.option('--junit-report',
              help="Write the junit report file. "
                   "This will contain a test result for "
                   "every packages file entry, entries wih "
                   "mismatched SHAs are reported as failed tests")
@click.pass_context
def find_and_verify_packages_files(ctx, yaml_report, junit_report):
    workers = ctx.obj['workers']
    repo_path = ctx.obj['repo_path']
    results = {}
    packages_files = ['{}/Packages'.format(p) for p, _, f
                      in os.walk(repo_path) if "Packages" in f]
    total = len(packages_files)
    for i, packages_file in enumerate(packages_files):
        try:
            with open(packages_file) as pf:
                print("Packages File {i} of {t}".format(i=i, t=total))
                results[packages_file] = _verify_packages_file(
                    pf, repo_path, workers,
                    should_write_hash_cache=False)
        except Exception as e:
            print("Error checking packages file {pf}: {e}"
                  .format(pf=packages_file, e=e))
            traceback.print_exc(e)

    write_hash_cache()

    # The yaml report should only include problematic packages
    if yaml_report:
        yaml_data = {}
        for pf, packages in results.items():
            yaml_data[pf] = [p for p in packages if p['SHA1'] != p['disksha']]
        with open(yaml_report, 'w') as yrf:
            yrf.write(yaml.dump(yaml_data, default_flow_style=False))

    # The Junit report should include all packages,
    # but mark problematic packages as failures
    if junit_report:
        cases = []
        for pkglist in results.values():
            for pkg in pkglist:
                case = TestCase('{p[Package]}-{p[Version]}'.format(p=pkg),
                                '{p[repo_name]}.{p[Distribution]}'
                                '.{p[Architecture]}'
                                .format(p=pkg),
                                1,
                                '',
                                ''
                                )
                if pkg['SHA1'] != pkg['disksha']:
                    case.add_failure_info(
                        message='SHA Mismatch index sha: {p[SHA1]} '
                                'disk sha: {p[disksha]}'.format(p=pkg))
                cases.append(case)
        ts = TestSuite("RepoVerification", cases)
        with open(junit_report, 'w') as jurf:
            jurf.write(TestSuite.to_xml_string([ts]))


if __name__ == "__main__":
    cli()
