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

# Set locale
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Auto-activate base
RUN conda config --set auto_activate_base true

# Install conda-lock <1.5
RUN conda install -c conda-forge 'conda-lock<1.5' -y

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

USER $USERNAME
WORKDIR /workspace

# ------------------------------
# Clone and build Chipyard (cached)
# ------------------------------
ARG CHIPYARD_REPO=https://github.com/AnshKetchum/chipyard.git 
ARG CHIPYARD_BRANCH=main 
RUN git clone --branch $CHIPYARD_BRANCH $CHIPYARD_REPO chipyard 
 
WORKDIR /workspace/chipyard 
RUN chmod +x ./build-setup.sh && ./build-setup.sh -s 9

# ------------------------------
# Default command
# ------------------------------
CMD ["bash"]
