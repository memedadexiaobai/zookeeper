#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

# use POSIX interface, symlink is followed automatically
# BASH_SOURCE 是一个特殊的 Bash 变量，它包含当前脚本的路径和文件名。
# -$0 是为了提供一个默认值，如果 BASH_SOURCE 未定义的话，就使用 $0，即脚本的名称（如果脚本是通过符号链接运行的，$0 可能是链接的路径）。
ZOOBIN="${BASH_SOURCE-$0}"
# dirname 是一个命令行工具，用于从给定的路径中提取目录部分。
ZOOBIN="$(dirname "${ZOOBIN}")"
ZOOBINDIR="$(cd "${ZOOBIN}"; pwd)"

if [ -e "$ZOOBIN/../libexec/zkEnv.sh" ]; then
  . "$ZOOBINDIR"/../libexec/zkEnv.sh
else
  . "$ZOOBINDIR"/zkEnv.sh
fi

usage() {
  # the configfile will be properly formatted as long as the
  # configfile path is less then 40 chars, otw the line will look a
  # bit weird, but otherwise it's fine
  printf "usage: $0 <parameters>
  Optional parameters:
     -h                                                    Display this message
     --help                                                Display this message
     --configfile=%-40s ZooKeeper config file
     --myid=#                                              Set the myid to be used, if any (1-255)
     --force                                               Force creation of the data/txnlog dirs
" "$ZOOCFG"
  exit 1
}

# 检查上一条命令执行是否失败的经典写法，
#   核心逻辑是：如果上一条命令执行出错（返回非 0 状态码），就调用 usage 函数（通常是打印使用帮助）并退出脚本，退出码设为 1（表示执行失败）。
# $? 是 Shell 中内置的特殊变量，表示 “上一条命令执行后的退出状态码”；
#   0：表示上一条命令成功执行（比如 ls 找到文件、cd 进入有效目录）；
#   非 0（1~255）：表示上一条命令执行失败（不同数字可代表不同错误类型，比如 1 是通用错误，2 是参数错误）。
# [ ... ]：等价于 test ...，是 Shell 中用于条件测试的语法（注意 [ 后、] 前必须有空格）；
if [ $? != 0 ] ; then
    usage
    exit 1
fi

initialize() {
    if [ ! -e "$ZOOCFG" ]; then
        echo "Unable to find config file at $ZOOCFG"
        exit 1
    fi

#  grep "^[[:space:]]*dataDir" "$ZOOCFG"
#     ^：匹配行的开头（确保只找以 dataDir 开头的行，排除注释 / 中间包含的情况）；
#     [[:space:]]*：匹配任意数量的空白字符（空格 / 制表符），兼容配置文件中 dataDir 前有缩进的情况（比如 dataDir=/tmp/zookeeper）；
#     dataDir：精准匹配配置项关键字；
#     "$ZOOCFG"：ZooKeeper 配置文件路径（比如 /etc/zookeeper/zoo.cfg）。
#     作用：从配置文件中筛选出包含 dataDir 的有效配置行（排除注释行 #dataDir=xxx
# sed -e 's/.*=//'
#   s/原内容/替换内容/：sed 的替换语法，把 “原内容” 换成 “替换内容”；
#   .*=：正则匹配 “任意字符（.*）直到最后一个等号（=）”；
#   替换内容为空（//）：即删除等号及前面的所有内容，只保留等号后的路径。
#   作用：把 dataDir=/tmp/zookeeper 这类字符串，处理成 /tmp/zookeeper。
#   整体：把 grep 找到的配置行，通过 sed 提取路径后，赋值给变量 ZOO_DATADIR。
#  为什么用 sed -e 's/.*=//' 而不是你之前问的 ${1#*=}？
#     ${变量#*=} 是 Shell 内置的字符串处理，只能处理 “第一个等号”；
#     sed 's/.*=//' 是正则处理，会匹配 “最后一个等号”（比如配置行是 dataDir=/data=zk，前者会得到 /data=zk，后者会得到 zk）；
#     ZooKeeper 配置中 dataDir 的值不会包含等号，所以两种方式效果一致，但 sed 是处理文件内容的通用方式，更适配 “从文件提取” 的场景。
    ZOO_DATADIR="$(grep "^[[:space:]]*dataDir" "$ZOOCFG" | sed -e 's/.*=//')"
    ZOO_DATALOGDIR="$(grep "^[[:space:]]*dataLogDir" "$ZOOCFG" | sed -e 's/.*=//')"

    if [ -z "$ZOO_DATADIR" ]; then
        echo "Unable to determine dataDir from $ZOOCFG"
        exit 1
    fi

    if [ $FORCE ]; then
        echo "Force enabled, data/txnlog directories will be re-initialized"
    else
        # we create if version-2 exists (ie real data), not the
        # parent. See comments in following section for more insight
        if [ -d "$ZOO_DATADIR/version-2" ]; then
            echo "ZooKeeper data directory already exists at $ZOO_DATADIR (or use --force to force re-initialization)"
            exit 1
        fi

        if [ -n "$ZOO_DATALOGDIR" ] && [ -d "$ZOO_DATALOGDIR/version-2" ]; then
            echo "ZooKeeper txnlog directory already exists at $ZOO_DATALOGDIR (or use --force to force re-initialization)"
            exit 1
        fi
    fi

    # remove the child files that we're (not) interested in, not the
    # parent. this allows for parent to be installed separately, and
    # permissions to be set based on overarching requirements. by
    # default we'll use the permissions of the user running this
    # script for the files contained by the parent. note also by using
    # -p the parent(s) will be created if it doesn't already exist
    rm -rf "$ZOO_DATADIR/myid" 2>/dev/null >/dev/null
    rm -rf "$ZOO_DATADIR/version-2" 2>/dev/null >/dev/null
    mkdir -p "$ZOO_DATADIR/version-2"

    if [ -n "$ZOO_DATALOGDIR" ]; then
        rm -rf "$ZOO_DATALOGDIR/myid" 2>/dev/null >/dev/null
        rm -rf "$ZOO_DATALOGDIR/version-2" 2>/dev/null >/dev/null
        mkdir -p "$ZOO_DATALOGDIR/version-2"
    fi

    if [ $MYID ]; then
        echo "Using myid of $MYID"
        echo $MYID > "$ZOO_DATADIR/myid"
    else
        echo "No myid provided, be sure to specify it in $ZOO_DATADIR/myid if using non-standalone"
    fi

    touch "$ZOO_DATADIR/initialize"
}

# while [ ! -z "$1" ]：循环判断 “第一个参数（$1）是否非空”，只要有参数就继续处理；
# case "$1"：匹配当前第一个参数，执行对应逻辑（赋值变量 / 打印帮助 / 报错）；
# shift N：将命令行参数列表 “左移 N 位”（比如 shift 2 会让 $3 变成新的 $1），实现 “逐个消费参数”
# $1：Shell 内置变量，表示第一个命令行参数（$2 是第二个，依此类推）；
# -z "$1"：测试 $1 是否为空字符串（-z = zero length）；
# !：取反，! -z "$1" 即 “$1 非空”；
while [ ! -z "$1" ]; do # 只要第一个参数非空，就循环处理
  case "$1" in # 匹配当前第一个参数
    --configfile)  # 匹配 "--configfile" 形式的参数
      ZOOCFG=$2; shift 2  # 把第二个参数赋值给 ZOOCFG，然后左移2位（跳过这两个参数）
      ;;
    --configfile=?*)  # 匹配 "--configfile=xxx" 形式的参数（=后至少1个字符） ? 匹配 “至少 1 个字符”，* 匹配 “任意字符”，确保 = 后有值（
# ${变量#匹配模式}：从变量开头删除 “匹配模式” 的最短匹配部分；
# *=：匹配 “任意字符 + =”，所以 ${1#*=} 就是删除 --configfile= 这部分，只保留后面的 my.cfg；
# 示例：$1="--configfile=my.cfg" → ${1#*=} = my.cfg。
      ZOOCFG=${1#*=}; shift 1  # 截取=后的内容赋值给ZOOCFG，左移1位（跳过这个参数）
      ;;
    --myid)
      MYID=$2; shift 2
      ;;
    --myid=?*)
#  1 是变量名，# 是截取运算符
#
      MYID=${1#*=}; shift 1
      ;;
    --force)
      FORCE=1; shift 1
      ;;
    -h)
      usage
      ;; 
    --help)
      usage
      ;; 
    *)
      echo "Unknown option: $1"
      usage
      exit 1 
      ;;
  esac
done
initialize
