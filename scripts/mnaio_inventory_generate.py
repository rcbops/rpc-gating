#!/usr/bin/env python

import os
import sys

import yaml

# By default all hosts will be added to the pxe_servers group which is why it's
#  pre-defined.
INVENTORY = {
  'pxe_servers': {
      'hosts': dict()
  },
}

# Extra nodes are added via environment variables. The specs are unique entries
#  used to seperate the node types and reduce generic resouce consumption.
EXTRA_NODES = {
    'compute': {
        'nodes': int(os.getenv('ADDITIONAL_COMPUTE_NODES', 0)),
        'specs': {
            'server_vm_ram': 8192,
            'server_vm_vcpus': 4
        },
    },
    'cinder': {
        'nodes': int(os.getenv('ADDITIONAL_VOLUME_NODES', 0)),
        'specs': {
            'server_vm_ram': 2048,
            'server_vm_vcpus': 2
        },
    }
}


def host_skel():
    """Prep the dynamic inventory extension."""

    node_count = 0
    for k, v in EXTRA_NODES.items():
        for c in range(0, v['nodes']):
            node_count += 1
            host = "%s-e%d" % (k, c + 1)
            inv_entry = "%s_hosts" % k
            hostvars = host_ops(
                hostvars=v['specs'].copy(),
                hostname=host,
                num=node_count
            )
            if inv_entry not in INVENTORY:
                INVENTORY[inv_entry] = {'hosts': dict()}
            INVENTORY[inv_entry]['hosts'][host] = hostvars
            INVENTORY['pxe_servers']['hosts'][host] = hostvars


def host_ops(hostvars, hostname, num):
    """Update the hostvars for a given host entry.

    :returns: dict
    """
    # The last octet assigned in the stock inventory is 150, we allocating
    # from 200 to avoid conflicts.
    ip_last_octet = 200 + num
    mac_last_octet = 20 + num

    # Update the existing hostvar entry with the complete variable set.
    hostvars.update({
      "ansible_host": "10.0.236.%s" % ip_last_octet,
      'server_hostname': hostname,
      'server_vm': True,
      'server_preseed_ks': 'vm',
      'server_vm_fixed_addr': '10.0.2.%s' % ip_last_octet,
      'server_vm_primary_network': 'dhcp',
      'server_mac_address': '52:54:00:bd:80:%s' % mac_last_octet,
      'ansible_os_family': r'{{ images[default_vm_image]["image_type"] }}',
      'server_default_interface': 'eth0',
      'server_extra_options': '',
      'server_networks': {
        'flat': {
          'inet_type': 'static',
          'vm_int_iface': 'vm-br-eth2',
          'iface': 'eth2',
          'address': '10.0.248.%s/22' % ip_last_octet
        },
        'mgmt': {
          'inet_type': 'static',
          'vm_int_iface': 'vm-br-eth1',
          'iface': 'eth1',
          'address': '10.0.236.%s/22' % ip_last_octet
        },
        'dhcp': {
          'inet_type': 'dhcp',
          'vm_int_iface': 'vm-br-dhcp',
          'iface': 'eth0'
        },
        'vlan': {
          'inet_type': 'manual',
          'vm_int_iface': 'vm-br-eth3',
          'iface': 'eth3'
        },
        'storage': {
          'inet_type': 'static',
          'vm_int_iface': 'vm-br-eth5',
          'iface': 'eth5',
          'address': '10.0.244.%s/22' % ip_last_octet
        },
        'vxlan': {
          'inet_type': 'static',
          'vm_int_iface': 'vm-br-eth4',
          'iface': 'eth4',
          'address': '10.0.240.%s/22' % ip_last_octet
        }
      },
      'server_image': r'{{ default_vm_image }}'
    })
    return hostvars


def main():
    """Run the main application."""

    host_skel()
    with open(sys.argv[1], 'w') as f:
        f.write(
            yaml.safe_dump(
                INVENTORY,
                default_flow_style=False,
                width=1000
            )
        )


if __name__ == '__main__':
    main()
