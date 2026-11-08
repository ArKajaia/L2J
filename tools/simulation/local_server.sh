#!/bin/bash
# Brings up a local game server for simulations (TvT batches, the A/B arena, the matrix) in a fresh
# Linux container (Ubuntu 24.04, run as root): JDK 25, Ant and MariaDB, the database, a build in
# ../build/server, SimulationWebOpenAccess on, and the server started in the background.
#
#   tools/simulation/local_server.sh            build and (re)start
#   tools/simulation/local_server.sh --no-build restart the last build
#   tools/simulation/local_server.sh --stop     stop the server
#
# The web API is then at http://127.0.0.1:8788 (see tools/simulation/README.md), the server log in
# ../build/server/game/log/stdout.log. A full ant build deletes build/server: this script keeps
# build/server/game/log (simulation results included) across rebuilds.
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
SERVER=$(dirname "$REPO")/build/server
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

if [ "$1" = "--stop" ]; then
	pkill -f GameServer.jar && echo "Server stopped." || echo "No server running."
	exit 0
fi

# 1. JDK 25, Ant, MariaDB.
if [ ! -x "$JAVA_HOME/bin/java" ] || ! command -v ant >/dev/null || ! command -v mysqld_safe >/dev/null; then
	apt-get update -q
	DEBIAN_FRONTEND=noninteractive apt-get install -y -q openjdk-25-jdk-headless ant mariadb-server
fi

# 2. MariaDB running.
if ! mysqladmin ping --silent 2>/dev/null; then
	(mysqld_safe --user=mysql >/dev/null 2>&1 &)
	for i in $(seq 1 30); do mysqladmin ping --silent 2>/dev/null && break; sleep 1; done
fi

# 3. The database (dist/game/config/Database.ini: l2jmobiush5, root/root), once.
if ! mysql -uroot -proot -e "USE l2jmobiush5" 2>/dev/null; then
	mysql -uroot -e "CREATE DATABASE IF NOT EXISTS l2jmobiush5 CHARACTER SET utf8mb4; ALTER USER 'root'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD('root'); FLUSH PRIVILEGES;"
	for f in "$REPO"/dist/db_installer/sql/game/*.sql "$REPO"/dist/db_installer/sql/login/*.sql; do
		mysql -uroot -proot l2jmobiush5 < "$f"
	done
	echo "Database l2jmobiush5 created."
fi

pkill -f GameServer.jar && sleep 3 || true

# 4. Build, keeping the logs.
if [ "$1" != "--no-build" ]; then
	KEEP=""
	if [ -d "$SERVER/game/log" ]; then
		KEEP=$(mktemp -d)
		mv "$SERVER/game/log" "$KEEP/log"
	fi
	(cd "$REPO" && ant > /tmp/l2j-ant.log 2>&1) || { echo "Build failed, see /tmp/l2j-ant.log"; exit 1; }
	if [ -n "$KEEP" ]; then
		mv "$KEEP/log" "$SERVER/game/log"
		rmdir "$KEEP"
	fi
	echo "Built into $SERVER."
fi

# 5. Simulations through the web API without a GM (test servers only).
cd "$SERVER/game"
sed -i 's/^SimulationWebOpenAccess = False/SimulationWebOpenAccess = True/' config/Custom/FakeSimulation.ini

# 6. Start, and wait until it is up (about 80 seconds).
mkdir -p log
[ -f log/stdout.log ] && mv log/stdout.log "log/$(date +%Y-%m-%d_%H-%M-%S)_stdout.log"
(nohup java $(cat java.cfg) -jar ../libs/GameServer.jar > log/stdout.log 2>&1 &)
for i in $(seq 1 300); do
	grep -q "Server loaded in" log/stdout.log 2>/dev/null && break
	if ! pgrep -f GameServer.jar >/dev/null; then echo "The server stopped, see $SERVER/game/log/stdout.log"; exit 1; fi
	sleep 2
done
grep "Server loaded in" log/stdout.log
echo "Web API: http://127.0.0.1:8788 (e.g. curl -X POST http://127.0.0.1:8788/api/tvt/batch -d 'count=10&level=80&a=legacy&b=best&size=30&minutes=5')"
