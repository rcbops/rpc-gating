#!/usr/bin/env python2
#
# Copyright 2016, Rackspace US, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Basic tool to save an image of a server in Rackspace Cloud. While
   in general it is preferred to use the nova/openstack CLI to do this,
   in our Jenkins jobs we only have access to pyrax and the nova CLI
   that comes with it which does not work. Until we move to using the
   shade-based Ansible modules this little tool will be necessary."""

import argparse
import pyrax


def main():
    """Run the main application."""

    # Setup argument parsing
    parser = argparse.ArgumentParser(
        description='Basic pyrax CLI utilities',
        epilog='Licensed "Apache 2.0"')

    parser.add_argument(
        '-c',
        '--credentialsfile',
        help='<Required> The path to the pyrax.cfg file',
        default='~/.pyrax.cfg'
    )

    parser.add_argument(
        '-r',
        '--cloudregion',
        help='<Required> The region to execute commands against',
        required=True
    )

    parser.add_argument(
        '-s',
        '--serveruuid',
        help='<Required> The server uuid to execute commands against',
        required=True
    )

    parser.add_argument(
        '-i',
        '--imagename',
        help='<Required> The name to use for the saved image',
        required=True
    )

    # Parse arguments
    args = parser.parse_args()

    # Set the identity type for Rackspace Cloud
    pyrax.set_setting('identity_type',  'rackspace')

    # Set the credentials
    pyrax.set_credential_file(args.credentialsfile)

    # Create the cloudservers object
    cs = pyrax.connect_to_cloudservers(region=args.cloudregion)

    # Get the list of current images with the same name
    current_images = [
        image for image in cs.images.list()
        if hasattr(image, "server") and image.name == args.imagename
    ]

    # Request the image creation (it returns the id, not an object)
    saved_image_id = cs.servers.create_image(args.serveruuid, args.imagename)

    # Get the image object
    saved_image = cs.images.get(saved_image_id)

    # Wait until the image creation returns a result
    saved_image = pyrax.utils.wait_until(
        saved_image, "status", ["ACTIVE", "ERROR"], attempts=0
    )

    # Delete all previous images of the same name if the resulting image
    # status is 'ACTIVE'
    if saved_image.status == "ACTIVE":
        for image in current_images:
            image.delete()


if __name__ == "__main__":
    main()
