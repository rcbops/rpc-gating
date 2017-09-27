#!/usr/bin/env python

import os
import json

inventory = {
  'all': [],
  'pxe_servers': [],
  'cinder_hosts': [],
}
hosts = []
hostvars = {}

compute_count = int(os.getenv('ADDITIONAL_COMPUTE_NODES', 0))
cinder_count = int(os.getenv('ADDITIONAL_VOLUME_NODES', 0))

for c in range(0, compute_count):
    # The stock inventory already has a compute1 and compute2, so we want to
    # adding additional compute hosts as compute3.
    host = "compute%d" % (3 + c)
    hosts.append(host)
    inventory['all'].append(host)
    inventory['pxe_servers'].append(host)
    inventory["compute_hosts"].append(host)

for c in range(0, cinder_count):
    # The stock inventory already has a cinder1 and cinder2, so we want to
    # adding additional cinder hosts as cinder3.
    host = "cinder%d" % (3 + c)
    hosts.append(host)
    inventory['all'].append(host)
    inventory['pxe_servers'].append(host)
    inventory["cinder_hosts"].append(host)

for i, host in enumerate(hosts):
    # The last octet assigned in the stock inventory is 150, we allocating
    # from 200 to avoid conflicts.
    ip_last_octet = 200 + i
    mac_last_octet = 20 + i

    if host in inventory['cinder_hosts']:
        server_vm_ram = 2048
        server_vm_vcpus = 2
    else:
        server_vm_ram = 8192
        server_vm_vcpus = 4

    hostvars[host] = {
      "ansible_host": "10.0.236.%s" % ip_last_octet,
      'server_hostname': host,
      'server_vm': True,
      'server_preseed_ks': 'vm',
      'server_vm_fixed_addr': '10.0.2.%s' % ip_last_octet,
      'server_vm_vcpus': server_vm_vcpus,
      'server_vm_primary_network': 'dhcp',
      'server_mac_address': '52:54:00:bd:80:%s' % mac_last_octet,
      'server_vm_ram': server_vm_ram,
      'ansible_os_family': "{{ images[default_vm_image]['image_type'] }}",
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
      'server_image': '{{ default_vm_image }}'
    }

inventory["_meta"] = {"hostvars": hostvars}

print(json.dumps(inventory, indent=4))
