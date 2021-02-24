#!/bin/sh

LOCATION=`pwd`

cleanup () {
  rm -rf ./otoroshi
  rm -f ./otoroshi-dist.zip
  rm -f ./otoroshi.jar
}

prepare_build () {
  rm -rf ./otoroshi
  if [ ! -f ./otoroshi-dist.zip ]; then
    cd $LOCATION/../../otoroshi/javascript
    yarn install
    yarn build
    cd $LOCATION/../../otoroshi
    sbt dist
    sbt assembly
    cd $LOCATION
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-alpha.8.zip ./otoroshi-dist.zip
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
  fi
  unzip otoroshi-dist.zip
  mv otoroshi-1.5.0-alpha.8 otoroshi
  rm -rf otoroshi-dist.zip
  chmod +x ./otoroshi/bin/otoroshi
  mkdir -p ./otoroshi/imports
  mkdir -p ./otoroshi/leveldb
  mkdir -p ./otoroshi/logs
  touch ./otoroshi/logs/application.log
}

build_jdk8 () {
  docker build --no-cache -f ./Dockerfile-jdk8 -t otoroshi-jdk8 .
  docker build --no-cache -f ./Dockerfile-jdk8-jar -t otoroshi-jdk8-jar .
  docker tag otoroshi-jdk8 "maif/otoroshi:$1-jdk8"
  docker tag otoroshi-jdk8-jar "maif/otoroshi:$1-jdk8-jar"
}

build_jdk11 () {
  docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
  docker build --no-cache -f ./Dockerfile-jdk11-jar -t otoroshi-jdk11-jar .
  docker tag otoroshi-jdk11-jar "maif/otoroshi:latest" 
  docker tag otoroshi-jdk11-jar "maif/otoroshi:$1" 
  docker tag otoroshi-jdk11 "maif/otoroshi:$1-jdk11-jar"
  docker tag otoroshi-jdk11-jar "maif/otoroshi:$1-jdk11-jar"
}

build_jdk15 () {
  docker build --no-cache -f ./Dockerfile-jdk15 -t otoroshi-jdk15 .
  docker build --no-cache -f ./Dockerfile-jdk15-jar -t otoroshi-jdk15-jar .
  docker tag otoroshi-jdk15 "maif/otoroshi:$1-jdk15"
  docker tag otoroshi-jdk15-jar "maif/otoroshi:$1-jdk15-jar"
}

build_graal () {
  docker build --no-cache -f ./Dockerfile-graal -t otoroshi-graal .
  docker tag otoroshi-graal "maif/otoroshi:$1-graal"
}

echo "Docker images for otoroshi version $2"

case "${1}" in
  prepare-build)
    prepare_build
    ;;
  cleanup)
    cleanup
    ;;
  build-jdk8)
    prepare_build
    build_jdk8 $2
    cleanup
    ;;
  build-jdk11)
    prepare_build
    build_jdk11 $2
    cleanup
    ;;
  build-jdk15)
    prepare_build
    build_jdk15 $2
    cleanup
    ;;
  build-graal)
    prepare_build
    build_graal $2
    cleanup
    ;;
  build-all)
    prepare_build
    build_jdk8 $2
    build_jdk11 $2
    build_jdk15 $2
    cleanup
    ;;
  push-all)
    prepare_build
    build_jdk8 $2
    build_jdk11 $2
    build_jdk15 $2
    build_graal $2
    cleanup
    docker push "maif/otoroshi:$2"
    docker push "maif/otoroshi:$2-jdk8"
    docker push "maif/otoroshi:$2-jdk8-jar"
    docker push "maif/otoroshi:$2-jdk11"
    docker push "maif/otoroshi:$2-jdk11-jar"
    docker push "maif/otoroshi:$2-jdk15"
    docker push "maif/otoroshi:$2-jdk15-jar"
    docker push "maif/otoroshi:$2-graal"
    docker push "maif/otoroshi:latest"
    ;;
  build-and-push-snapshot)
    NBR=`date +%s`
    echo "Will build version 1.5.0-alpha.8-$NBR"
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-alpha.8.zip otoroshi-dist.zip
    prepare_build
    docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "maif/otoroshi:1.5.0-alpha.8-$NBR"
    docker tag otoroshi-jdk11 "maif/otoroshi:dev"
    cleanup
    docker push "maif/otoroshi:1.5.0-alpha.8-$NBR"
    docker push "maif/otoroshi:dev"
    ;;
  build-and-push-local)
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-alpha.8.zip otoroshi-dist.zip
    prepare_build
    docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "registry.oto.tools:5000/maif/otoroshi:1.5.0-local"
    cleanup
    docker push "registry.oto.tools:5000/maif/otoroshi:1.5.0-local"
    ;;
  build-snapshot)
    NBR=`date +%s`
    echo "Will build version 1.5.0-alpha.8-$NBR"
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-alpha.8.zip otoroshi-dist.zip
    prepare_build
    docker build --no-cache -f ./Dockerfile-jdk11 -t otoroshi-jdk11 .
    docker tag otoroshi-jdk11 "maif/otoroshi:1.5.0-alpha.8-$NBR"
    docker tag otoroshi-jdk11 "maif/otoroshi:dev"
    cleanup
    ;;
  prepare)
    cp ../../otoroshi/target/universal/otoroshi-1.5.0-alpha.8.zip ./otoroshi-dist.zip
    cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
    ;;
  *)
    echo "Build otoroshi docker images"
    ;;
esac

exit ${?}