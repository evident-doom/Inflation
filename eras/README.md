# Era profiles

An era profile is a properties file of ordinary config keys that sets how a world starts in that era. Name it in the
config file with `era=NAME` (`eras/NAME.properties`, looked for beside the config file, then in the directory the run starts
in, then in the folders above them or above the running code, so a run in its own folder under the project finds the project's). Its keys are read as if they stood in the config file; the config file's own keys take precedence.

A profile sets only the start: which share of bands start as villages or herders (`t.startSettled`, `t.startHerders`), how
domesticated the villages' grain is (`t.startDomestic`), and which ways and tools every band knows (`t.startSkills`,
`t.startTools`). After the start the world runs by its own rules: nothing in a profile keeps a village a village or a way known.

It works on a fresh world and on one carried on from a save (`hexFauna --load`, `testWorld`): laid on a settled Natufian world,
it seeds the era's villages and herds among the bands it carries on with and raises every band's ways and tools to the
profile's (raising only, so applying it again changes nothing more; villages and herds are started anew each time).

| Profile | Era | Approximate dates |
|---|---|---|
| natufian | Late Epipalaeolithic Levant: the model's own start (no keys) | 12,500-9,700 BCE |
| ppna | Pre-Pottery Neolithic A | 10,000-8,800 BCE |
| ppnb | Pre-Pottery Neolithic B | 8,800-7,000 BCE |

Every value in the profiles is an estimate; the dates and sources in their comments are from memory and are to be checked.
There are no later profiles yet: pottery, metals and the rest have no mechanism in the model.
