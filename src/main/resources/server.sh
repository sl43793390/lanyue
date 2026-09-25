#!/bin/sh
# 用法：sh server.sh {start|stop|restart|status} <jar包名>
# 示例：sh server.sh start logviewer.jar
#
# 兼容性口径（2026-09 与卧龙确认）：本脚本与 jar/Tomcat/通用项目管理发出的全部命令
# 只用 POSIX sh + coreutils + procps-ng 的公共交集，已核对
# CentOS 7.9 / RockyLinux 8 / RockyLinux 9 / Ubuntu 22.04 四个发行版无需分支：
#   - ps -C java -f --width 1000：procps-ng 全系支持（CentOS7=3.3.10，Rocky8/9、Ubuntu22 均可）
#   - ps -ef | grep / awk / kill -9 / nohup / chmod / head -n / command -v / test -x：
#     全部为 POSIX 或四版 coreutils 行为一致
#   - 未用 systemctl / ss / pgrep 参数等发行版差异点
# 注意：/bin/sh 在 Ubuntu 22 是 dash，本脚本保持 POSIX 语法（不要加 [[ ]] 等bash语法）。
APP_NAME="$2"

usage() {
    echo "Usage: sh server.sh [start|stop|restart|status] <jar包名>"
    exit 1
}

if [ -z "$APP_NAME" ]; then
    usage
fi

#检查程序是否在运行
is_exist(){
  PID=$(ps -C java -f --width 1000 | grep "$APP_NAME" | grep -v grep | awk '{print $2}')
  #如果不存在返回1，存在返回0
  if [ -z "${PID}" ]; then
   return 1
  else
   return 0
  fi
}

#启动方法
start(){
  is_exist
  if [ $? -eq "0" ]; then
    echo "${APP_NAME} is already running. pid=${PID} ."
  else
    # JVM 参数必须写在 -jar 之前。-jar 后面紧跟的必须是 jar 包路径，
    # 写成 "java -jar -Xmx1024m app.jar" 时 java 会把 -Xmx1024m 当 jar 包，报 Unable to access jarfile
    nohup java -Xmx1024m -Xms512m -jar "${APP_NAME}" > app.log 2>&1 &
    echo "${APP_NAME} start success"
  fi
}

#停止方法
stop(){
  is_exist
  if [ $? -eq "0" ]; then
    kill -9 $PID
	echo "${APP_NAME} was successfully stopped"
  else
    echo "${APP_NAME} is not running"
  fi
}

#输出运行状态
status(){
  is_exist
  if [ $? -eq "0" ]; then
    echo "${APP_NAME} is running. Pid is ${PID}"
  else
    echo "${APP_NAME} is NOT running."
  fi
}

#重启
restart(){
  stop
  sleep 3
  start
}

#根据输入参数，选择执行对应方法，不输入则执行使用说明
case "$1" in
  "start")
    start
    ;;
  "stop")
    stop
    ;;
  "status")
    status
    ;;
  "restart")
    restart
    ;;
  *)
    usage
    ;;
esac
