import json
import dateutil.parser

# These functions are not called anywhere directly, but are useful for
# loading into an interactive environment for inspecting a json build
# data file.


# load build data
def loadbd(filename):
    data = json.load(open(filename))
    builds = data['builds']
    failures = data['failures']

    # transform the uuids used to reference objects in json, back into
    # object references
    # also convert iso 8601 timestamps into date objects.
    for b in builds.values():
        b['failures'] = [failures[uuid] for uuid in b['failures']]
        b['timestamp'] = dateutil.parser.parse(b['timestamp'])
    for f in failures.values():
        f['build'] = builds[f['build']]
    return (data, builds.values(), failures.values())


# get builds and failures for one day
def objectsForDay(data, year, month, day):
    def istd(b):
        return (b['timestamp'].day == day
                and b['timestamp'].month == month
                and b['timestamp'].year == year)
    builds = data['builds'].values()
    failures = data['failures'].values()
    daybuilds = [b for b in builds if istd(b)]
    dayfailures = [f for f in failures if istd(f['build'])]
    return (daybuilds, dayfailures)
