from setuptools import setup, find_packages

setup(
    name='webhooktranslator',
    description='Receive alerts from MaaS and create jira issues',
    url='https://github.com/rcbops/rpc-gating/tree/master/webhooktranslator',
    author='Rackspace Private Cloud Team',
    author_email='hugh@wherenow.org',
    license='Apache',
    packages=find_packages(),
    install_requires=[
        'jira==1.0.10',
        'flask==0.12.2',
        'flup==1.0.2'
    ],
    entry_points={
        'console_scripts': [
            'webhooktranslator=webhooktranslator.webhooktranslator:main',
            'webhooktranslator_wsgi=webhooktranslator.webhooktranslator:wsgi'
        ]
    }
)
