#!/usr/bin/env bash

set -xe
echo "Remove exited containers"
#https://docs.docker.com/engine/reference/commandline/ps/#examples
sudo docker ps -a -q --filter "status=exited" --filter "status=dead" | while read cid; do sudo docker rm $cid||:; done


# Commenting this out.  We no longer have an intrnal docker repo for these, so lets keep existing images until we repoace jenkins.
# echo "Remove old Images"
# Remove any images that have been created more than a day ago, unless it is
# the latest ubuntu image.
# diid=$(sudo docker images -a | grep -vP "^ubuntu.*latest" | egrep "[0-9]{2,} (hour|day|week)s?" | awk '{print $3}')
# for dimage in $diid; do sudo docker rmi -f ${dimage}||:; done
