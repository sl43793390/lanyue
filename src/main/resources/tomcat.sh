#!/bin/bash
#传入tomcat主路径
CATALINA_HOME=$2

start() {
    echo "Starting Tomcat..."
    sh $CATALINA_HOME/bin/startup.sh
}

stop() {
    echo "Stopping Tomcat..."
    sh $CATALINA_HOME/bin/shutdown.sh
}

restart() {
    stop
    sleep 5
    start
}

case $1 in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    *)
        echo "Usage: $0 {start|stop|restart}"
        exit 1
        ;;
esac

exit 0