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
    openssh-server \
    openssh-client \
    rsync \
    less \
    vim \
    nano \
    locales \
    build-essential \
    ca-certificates \
    kmod \
    tmux \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install LibGL 
RUN apt-get update && apt-get install -y \
    libgl1-mesa-glx \
    libgl1-mesa-dri \
    libglu1-mesa \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxi6 \
    libxrandr2 \
    libxcursor1 \
    libxinerama1 \
    libxft2 \
    && rm -rf /var/lib/apt/lists/*


# Install CPIO to get firemarshal running
RUN sudo apt update
RUN sudo apt install cpio 

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
RUN conda install -c conda-forge 'libstdcxx-ng>=12' -y
# Install firtool
RUN conda config --add channels ucb-bar && \
    conda config --set channel_priority strict && \
    conda install -y firtool
# ------------------------------
# SSH setup
# ------------------------------
RUN mkdir -p /run/sshd && \
    ssh-keygen -A && \
    echo "Port 22" >> /etc/ssh/sshd_config && \
    echo "PasswordAuthentication yes" >> /etc/ssh/sshd_config && \
    echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config && \
    echo "PermitRootLogin no" >> /etc/ssh/sshd_config
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
# Make /workspace writable and set up SSH directory
RUN mkdir -p /workspace && chown -R $USERNAME:$USERNAME /workspace && \
    mkdir -p /home/$USERNAME/.ssh && chmod 700 /home/$USERNAME/.ssh && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/.ssh
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

# Install chipyard deps
RUN chmod +x ./build-setup.sh && ./build-setup.sh -s 9 -s 10

# Install Sky130 vlsi deps
RUN chmod +x ./scripts/init-sky130.sh && source env.sh && ./scripts/init-sky130.sh

# Clone Hammer for any local development if need be
RUN git clone https://github.com/AnshKetchum/hammer /workspace/hammer

# ------------------------------
# Default command
# ------------------------------
CMD ["bash"]