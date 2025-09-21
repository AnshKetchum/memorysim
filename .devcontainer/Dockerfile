# Base image with Miniconda
FROM continuumio/miniconda3:latest
ENV DEBIAN_FRONTEND=noninteractive

# ------------------------------
# Install system packages
# ------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
    sudo \
    git \
    curl \
    wget \
    openssh-client \
    less \
    vim \
    nano \
    locales \
    build-essential \
    ca-certificates \
    tmux \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Fix locale setup - ensure it's in locale.gen first
RUN echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && \
    locale-gen en_US.UTF-8

# Set locale environment variables
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Set JVM encoding options to handle Unicode (fixes Shapeless error)
ENV SBT_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dfile.encoding.pkg=UTF-8"
ENV _JAVA_OPTIONS="-Dfile.encoding=UTF-8"
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

# Auto-activate base
RUN conda config --set auto_activate_base true

# Install conda-lock <1.5
RUN conda install -c conda-forge 'conda-lock<1.5' -y

# Install firtool
RUN conda config --add channels ucb-bar && \
    conda config --set channel_priority strict && \
    conda install -y firtool

# ------------------------------
# Create a non-root user for VSCode
# ------------------------------
ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=$USER_UID

RUN groupadd --gid $USER_GID $USERNAME \
    && useradd -s /bin/bash --uid $USER_UID --gid $USER_GID -m $USERNAME \
    && echo "$USERNAME ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers.d/$USERNAME \
    && chmod 0440 /etc/sudoers.d/$USERNAME

# Make /workspace writable
RUN mkdir -p /workspace && chown -R $USERNAME:$USERNAME /workspace

USER $USERNAME
WORKDIR /workspace

# ------------------------------
# Clone and build Chipyard (cached)
# ------------------------------
ARG CHIPYARD_REPO=https://github.com/AnshKetchum/chipyard.git 
ARG CHIPYARD_BRANCH=main 

RUN git clone --branch $CHIPYARD_BRANCH $CHIPYARD_REPO chipyard 
 
WORKDIR /workspace/chipyard 

# Hack: for all directories to be trusted
RUN git config --global --add safe.directory '*'

# Ensure encoding environment is set for the build user too
ENV SBT_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dfile.encoding.pkg=UTF-8"
ENV _JAVA_OPTIONS="-Dfile.encoding=UTF-8"


RUN chmod +x ./build-setup.sh && ./build-setup.sh -s 9 -s 10


# ------------------------------
# Default command
# ------------------------------
CMD ["bash"]