#!/bin/bash
# Heartbeat Server startup script

cd /home/awesomeshot5051/heartbeat_server
exec /home/awesomeshot5051/.sdkman/candidates/java/current/bin/java -cp . HeartbeatServer -v
