#!/bin/sh


brokerid=`/Users/1mole/Documents/Softwares/kafka_2.12-2.4.0/bin/zookeeper-shell.sh localhost:2181 <<< "get /controller" | grep brokerid|jq '.brokerid'`
container_id=`docker ps -aqf "name=ziggurat_kafka${brokerid}_1"`

echo "Disabling zookeeper access on broker kafka${brokerid}"

#docker exec -it  $container_id /bin/bash -c  "apt-get update && apt-get install -y iptables && iptables -A INPUT -s ziggurat_zookeeper_1.ziggurat_default -j DROP"
docker exec -it  $container_id /bin/bash -c  "iptables -A INPUT -s ziggurat_zookeeper_1.ziggurat_default -j DROP"





