FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
    build-essential \
    libreadline-dev \
    curl \
    unzip \
    git \
    libssl-dev \
    libz-dev \
    wget

# Install Lua 5.3
RUN wget https://www.lua.org/ftp/lua-5.3.5.tar.gz && \
    tar -zxf lua-5.3.5.tar.gz && \
    cd lua-5.3.5 && \
    make linux test && \
    make install

# Install LuaRocks
RUN curl -R -O https://luarocks.github.io/luarocks/releases/luarocks-3.5.0.tar.gz && \
    tar -xzvf luarocks-3.5.0.tar.gz && \
    cd luarocks-3.5.0 && \
    ./configure && \
    make bootstrap

# Clone and build wrk2 (ARM64 compatible fork)
RUN git clone https://github.com/AmpereTravis/wrk2-aarch64.git wrk2 && \
    cd wrk2 && \
    make && \
    cp wrk /usr/local/bin/

# Set working directory
WORKDIR /wrk2

# Set entrypoint
ENTRYPOINT ["wrk"]
CMD ["--version"]