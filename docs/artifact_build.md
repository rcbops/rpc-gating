# Artifacts for RPC-O

![Artifact Build Process](images/artifact-build-flow/artifact-build-flow.png)

## Apt
Apt artifacts are built using [Aptly](https://www.aptly.info/) on a long
running slave. The slave has a single executor so there's no need to
implement any locking in the job config as the locking is implicit. The
artifacts for all distributions needed for the series are handled by a
single job.

## Git
Git artifacts are a git clone, tarballed and compressed. They are therefore
not distribution-specific and are therefore only done once per series. A
per series lock is used to ensure that we do not do the pipeline twice at
the same time, resulting in one overwriting the other.

## Python
Python artifacts must be done once per distribution. We use a per-series
lock to prevent a pipeline from executing more than one at a time per series,
resulting in artifacts already built overwriting each other.

## Container
Container artifacts must be done once per distribution. We use a single lock
for all series and distributions due to a race condition when making changes
to the image index. See [RE-115](https://rpc-openstack.atlassian.net/browse/RE-115)
for more details.
