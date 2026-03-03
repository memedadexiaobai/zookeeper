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

# This script should be sourced into other zookeeper
# scripts to setup the env variables

# We use ZOOCFGDIR if defined,
# otherwise we use /etc/zookeeper
# or the conf directory that is
# a sibling of this script's directory.
# Or you can specify the ZOOCFGDIR using the
# '--config' option in the command line.

# ${VARIABLE:-default}：这是 Bash 中的一种参数扩展形式，称为默认值扩展。
# 它的含义是：
#   如果变量 VARIABLE 已经被赋值（即它非空），则使用该变量的值；
#   如果变量 VARIABLE 未被赋值（即它为空），则使用 -default 指定的默认值。
ZOOBINDIR="${ZOOBINDIR:-/usr/bin}"
ZOOKEEPER_PREFIX="${ZOOBINDIR}/.."

#check to see if the conf dir is given as an optional argument
# $# 是一个特殊参数，代表命令行参数的数量（不包括脚本名称本身）
if [ $# -gt 1 ]
then
    if [ "--config" = "$1" ]
	  then
#	    shift 是一个命令，用于将脚本的参数向左移动，即 $1（第一个参数）被移除，$2（第二个参数）成为新的 $1，以此类推。
#     第一次 shift 执行后，原先的 --config 参数被移除，下一个参数成为新的 $1
	      shift
	      confdir=$1
	      shift
	      ZOOCFGDIR=$confdir
    fi
fi

# [ "x$ZOCFGDIR" = "x" ] 是一个测试条件表达式，用于检查变量 ZOCFGDIR 是否已经被设置（即是否已经被赋予了一个非空的值）。
# x 前缀用于确保变量在比较时被当作字符串处理，即使变量为空或未设置。
# x 前缀是一个技巧，变量的值会在条件表达式中被展开，用于确保变量展开时不会因为变量为空而导致语法错误。
if [ "x$ZOOCFGDIR" = "x" ]
then
#  `-e` 测试操作用于检查文件或目录是否存在。
  if [ -e "${ZOOKEEPER_PREFIX}/conf" ]; then
    ZOOCFGDIR="$ZOOBINDIR/../conf"
  else
    ZOOCFGDIR="$ZOOBINDIR/../etc/zookeeper"
  fi
fi

# -f 参数通常用于 if 条件表达式中的 test 命令，用于检查文件是否存在
if [ -f "${ZOOCFGDIR}/zookeeper-env.sh" ]; then
#  执行命令了
  . "${ZOOCFGDIR}/zookeeper-env.sh"
fi

if [ "x$ZOOCFG" = "x" ]
then
    ZOOCFG="zoo.cfg"
fi

ZOOCFG="$ZOOCFGDIR/$ZOOCFG"

if [ -f "$ZOOCFGDIR/java.env" ]
then
    . "$ZOOCFGDIR/java.env"
fi

if [ "x${ZOO_LOG_DIR}" = "x" ]
then
    ZOO_LOG_DIR="$ZOOKEEPER_PREFIX/logs"
fi

if [ "x${ZOO_LOG4J_PROP}" = "x" ]
then
    ZOO_LOG4J_PROP="INFO,CONSOLE"
fi

# 检查环境变量 JAVA_HOME 是否未设置（即变量值为空或未定义）
# 检查 JAVA_HOME/bin/java 路径是否存在且为可执行文件
if [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    JAVA="$JAVA_HOME/bin/java"
# type -p 命令用于查找命令的路径
# type 是一个在 Unix 和 Linux 系统中广泛使用的 shell 命令，用于显示命令的类型。它主要用于查找和显示命令的路径，无论该命令是可执行文件、别名、函数还是内置命令。
#   -p 选项告诉 type 命令只输出命令的完整路径，如果命令存在于环境变量 PATH 中的话。如果命令不在 PATH 中，type -p 将返回一个非零的退出状态。
elif type -p java; then
    JAVA=java
else
    echo "Error: JAVA_HOME is not set and java could not be found in PATH." 1>&2
    exit 1
fi

#add the zoocfg dir to classpath
CLASSPATH="$ZOOCFGDIR:$CLASSPATH"

for i in "$ZOOBINDIR"/../zookeeper-server/src/main/resources/lib/*.jar
do
    CLASSPATH="$i:$CLASSPATH"
done

#make it work in the binary package
#(use array for LIBPATH to account for spaces within wildcard expansion)
# ${变量名} 表示取这个环境变量的实际值（比如可能是 /usr/local/zookeeper），加花括号是为了明确变量边界，避免和后面的字符混淆
# >：标准输出重定向符号（默认等价于 1>），表示把命令的标准输出（stdout） 重定向到指定位置。
# 2：代表标准错误输出（stderr）（比如 ls 找不到文件时会报错 “No such file or directory”）。
# &1：表示 “指向标准输出（stdout）当前的重定向目标”（也就是上面的 /dev/null）。
# 2>&1 的作用：把标准错误输出也重定向到和标准输出相同的位置（/dev/null），即使 ls 执行失败（比如没找到 jar 包），也不会在终端显示错误提示。
# 结尾的 ; Shell 中的命令分隔符，表示这是一条独立的命令，执行完这行后，后续可以接其他命令（比如根据这个命令的执行结果做判断）。
if ls "${ZOOKEEPER_PREFIX}"/share/zookeeper/zookeeper-*.jar > /dev/null 2>&1; then
#  加括号的核心目的，是把匹配到的多个 jar 包路径以数组形式存储，而不是当成单个字符串。
# 用数组而非字符串，是为了兼容含空格 / 特殊字符的路径，避免后续处理时分割错误；
  LIBPATH=("${ZOOKEEPER_PREFIX}"/share/zookeeper/*.jar)
else
  #release tarball format
  for i in "$ZOOBINDIR"/../zookeeper-*.jar
  do
    CLASSPATH="$i:$CLASSPATH"
  done
  LIBPATH=("${ZOOBINDIR}"/../lib/*.jar)
fi

# Bash 遍历数组的通用且推荐写法，核心优势是能保证数组的每个元素独立、完整地被遍历，不会因元素含空格 / 特殊字符而出错。
# ${LIBPATH[@]}：表示展开数组的所有元素，且每个元素作为独立的 “单元”；
# 外层的双引号 "${LIBPATH[@]}"：保护每个元素的完整性（比如元素含空格、通配符时，不会被 Shell 拆分或解析）；
# for i in ...：依次将数组的每个元素赋值给变量 i，完成遍历
for i in "${LIBPATH[@]}"
do
    CLASSPATH="$i:$CLASSPATH"
done

#make it work for developers
for d in "$ZOOBINDIR"/../build/lib/*.jar
do
   CLASSPATH="$d:$CLASSPATH"
done

for d in "$ZOOBINDIR"/../zookeeper-server/target/lib/*.jar
do
   CLASSPATH="$d:$CLASSPATH"
done

#make it work for developers
CLASSPATH="$ZOOBINDIR/../build/classes:$CLASSPATH"

#make it work for developers
CLASSPATH="$ZOOBINDIR/../zookeeper-server/target/classes:$CLASSPATH"

# 检测当前操作系统是否为 Windows 下的类 Unix 环境（Cygwin/MINGW） 的经典写法，
#   核心目的是根据系统类型设置 cygwin 变量为 true 或 false，方便后续脚本适配不同系统的路径、命令行为。
# `uname`：是 Shell 的命令替换语法（反引号），等价于 $(uname)，作用是执行 uname 命令并获取其输出。
# uname 命令：Unix/Linux 系统中用于打印系统信息，核心是返回 “操作系统内核名称”：
#   Linux 系统输出：Linux
#   macOS 系统输出：Darwin
#   Windows 下的 Cygwin/MINGW（比如 Git Bash、MinGW64）输出：CYGWIN_NT-10.0 或 MINGW64_NT-10.0 等。
# case 是 Shell 中用于匹配字符串并执行对应逻辑的语法，替代多段 if-elif-else，结构更清晰：
#   格式：case 待匹配字符串 in 匹配模式) 执行命令 ;; esac
#   ;;：表示 “匹配到该模式后停止，不再继续匹配”（类似其他语言的 break）。
# `uname` 和 $(uname) 效果完全一致，但 $() 更易读、支持嵌套（推荐），所以这段代码也可写成：
case "`uname`" in
    CYGWIN*|MINGW*) cygwin=true ;;
    *) cygwin=false ;;
esac

# 将 Unix 风格的类路径（CLASSPATH）转换为 Windows 原生可识别的路径格式，让 Java 能在 Windows 下的 Cygwin/MINGW 环境中正确识别类路径。
if $cygwin
then
# cygpath：Cygwin/MINGW 环境特有的工具，专门用于转换 Windows 路径和 Unix 路径；
#   关键参数：
#     -w（--windows）：将 Unix 风格路径转换为 Windows 风格路径；
#     -p（--path）：处理 “路径列表”（比如用 : 分隔的 CLASSPATH），将分隔符从 : 转为 Windows 特有的 ;；
#   `...`：命令替换，将 cygpath 的执行结果赋值给 CLASSPATH 变量；
#   "$CLASSPATH"：加双引号是为了保护路径中的空格 / 特殊字符，避免被拆分。
    CLASSPATH=`cygpath -wp "$CLASSPATH"`
fi

#echo "CLASSPATH=$CLASSPATH"

# default heap for zookeeper server
ZK_SERVER_HEAP="${ZK_SERVER_HEAP:-1000}"
export SERVER_JVMFLAGS="-Xmx${ZK_SERVER_HEAP}m $SERVER_JVMFLAGS"

# default heap for zookeeper client
ZK_CLIENT_HEAP="${ZK_CLIENT_HEAP:-256}"
export CLIENT_JVMFLAGS="-Xmx${ZK_CLIENT_HEAP}m $CLIENT_JVMFLAGS"
