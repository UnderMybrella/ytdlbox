#!/bin/bash

rm -rf workbench
git clone https://github.com/UnderMybrella/ytdlbox.git workbench
cd workbench || exit

./gradlew :server:shadowJar
mv server/build/libs/server-*.jar ../update.jar
cp distribution/manual/* ../

cd .. || exit

rm -rf workbench
screen -X -s ytdlbox quit
mv update.jar ytdlbox.jar
bash bootstrap.sh