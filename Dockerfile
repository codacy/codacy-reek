ARG GEM_FOLDER="/usr/local/bundle"

ARG BASE_IMAGE=ruby:4.0.10-alpine3.24

FROM $BASE_IMAGE as doc-generator

ARG GEM_FOLDER
ENV GEM_HOME=$GEM_FOLDER
ENV PATH $GEM_HOME/bin:$GEM_HOME/gems/bin:$PATH
WORKDIR /doc_generator

RUN apk add --no-cache make gcc libc-dev yaml-dev libstdc++ tzdata bash

COPY Gemfile .
COPY Gemfile.lock .

COPY docs docs

RUN echo 'gem: --no-document' > /etc/gemrc \
    && bundle install \
    && gem cleanup  \
    && rm -rf /tmp/* /var/cache/apk/*

RUN bundle config set --local path 'vendor/bundle' && bundle install && gem cleanup

FROM $BASE_IMAGE

ARG GEM_FOLDER
ENV GEM_HOME=$GEM_FOLDER
ENV PATH $GEM_HOME/bin:$GEM_HOME/gems/bin:$PATH

RUN apk add --no-cache openjdk17-jre bash libstdc++ tzdata

COPY --from=doc-generator /usr/local/bundle /usr/local/bundle
COPY --from=doc-generator /doc_generator/docs /docs

RUN adduser --uid 2004 --disabled-password --gecos "" docker
RUN chown -R docker:docker /usr/local/bundle
COPY target/universal/stage/ /workdir/
RUN chmod +x /workdir/bin/codacy-reek
USER docker
WORKDIR /workdir
ENTRYPOINT ["bin/codacy-reek"]
