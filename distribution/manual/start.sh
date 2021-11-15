#!/bin/bash

start_process() {
        savelog -n -c 14 ytdlbox.log
        savelog -n -c 4 ./heap_dumps/ytdlbox.hprof
        if [[ -f "ytdlbox.pid" ]]; then
                cat ytdlbox.pid | xargs kill -SIGKILL
        fi
        java -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="./heap_dumps/ytdlbox.hprof" -XX:OnOutOfMemoryError="cat ytdlbox.pid | xargs kill -SIGKILL" -jar ytdlbox.jar -Dlogback.configurationFile=logback.xml -config=application.conf > >(tee ytdlbox.log) 2>&1 &
        pid=$!
        echo $! > ytdlbox.pid
}

loop_process() {
        local pid=0
        start_process
        while ! wait $pid;
        do
                echo "Crashed with $?"
                echo "Rerunning..."
                sleep 2
                start_process
        done
}

savelog -n -c 7 script.log
loop_process | tee script.log