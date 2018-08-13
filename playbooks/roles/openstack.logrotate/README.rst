======================
ansible-role-logrotate
======================

Ansible role to manage Logrotate

* License: Apache License, Version 2.0
* Documentation: https://ansible-role-logrotate.readthedocs.org
* Source: https://git.openstack.org/cgit/openstack/ansible-role-logrotate
* Bugs: https://bugs.launchpad.net/ansible-role-logrotate

Description
-----------

Logrotate is designed to ease administration of systems that generate large
numbers of log files. It allows automatic rotation, compression, removal, and
mailing of log files. Each log file may be handled daily, weekly, monthly, or
when it grows too large.

Requirements
------------

Packages
~~~~~~~~

Package repository index files should be up to date before using this role, we
do not manage them.

Role Variables
--------------

Dependencies
------------

Example Playbook
----------------

.. code-block:: yaml

    - name: Install logrotate
      hosts: www
      roles:
        - ansible-role-logrotate
