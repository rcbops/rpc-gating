#!/usr/bin/env bash

set -xe
echo "Remove exited containers"
docker ps -a |awk '/Exited/{print $1}' |while read cid; do docker rm $cid||:; done

echo "Remove Images"
# If the image is in use, rmi (remove image) will fail even with force.
# Only images that aren't in use will be removed. Images that aren't in use
# but are used will be rebuild or downloaded. Force is required to remove tagged
# images and all the images created by the jenkins docker plugin are tagged.
docker images -q |while read diid; do docker rmi -f $diid ||:;  done
