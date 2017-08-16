#!/usr/bin/env bash

set -xe
echo "Remove exited containers"
#https://docs.docker.com/engine/reference/commandline/ps/#examples
(docker ps -a -q -f "status=exited"; docker ps -a -q -f "status=dead")| while read cid; do docker rm $cid||:; done

echo "Remove old Images"
# Remove any images that have been created more than a day ago, unless it is
# the latest ubuntu image.
diid=$(docker images -a | grep -vP "^ubuntu.*latest" | egrep "[0-9]{2,} (hour|day|week)s?" | awk '{print $3}')
for dimage in $diid; do docker rmi -f ${dimage}||:; done
