FROM circleci/clojure:lein-2.7.1
LABEL maintainer="hlship@gmail.com"

# Based on notes here:
# https://www.metachris.com/2017/01/how-to-install-nodejs-7-on-ubuntu-and-centos/

RUN curl -sL https://deb.nodesource.com/setup_7.x | sudo bash -
RUN sudo apt-get install -y nodejs


# To build & deploy:
# From .circleci folder:
#
#    docker build -t hlship/circleci-lein-node:2.7.1 .
#    docker push hlship/circleci-lein-node:2.7.1



