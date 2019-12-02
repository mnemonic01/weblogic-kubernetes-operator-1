#!/bin/bash
# Copyright 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

# Helm 

HELM_URL=https://get.helm.sh
HELM_TARBALL=helm-v2.7.2-linux-amd64.tar.gz
HELM_EXTRACTED_ARCHIVE="$(pwd)/linux-amd64/"

install_helm () {

  # Download and install helm
  curl -o "${HELM_TARBALL}" "${HELM_URL}/${HELM_TARBALL}"
  tar zxvf ${HELM_TARBALL}
  PATH=${PATH}:${HELM_EXTRACTED_ARCHIVE}
  export PATH
  rm -f ${HELM_TARBALL}

  # Install helm s3 plugin if not installed
  if [[ $(helm plugin list | grep "^s3") == "" ]]; then
    helm plugin install https://github.com/hypnoglow/helm-s3.git
  fi
}

install_helm



SCRIPTPATH="$( cd "$(dirname "$0")" > /dev/null 2>&1 ; pwd -P )"
WOCHARTPATH="$SCRIPTPATH/charts/weblogic-operator"

if uname | grep -q "Darwin"; then
    mod_time_fmt="-f %m"
else
    mod_time_fmt="-c %Y"
fi

# Find latest file in source directory
unset -v latest
for file in "$WOCHARTPATH"/*; do
  [[ $file -nt $latest ]] && latest=$file
done

srctime="$(stat $mod_time_fmt $latest)"

out="$(helm package $WOCHARTPATH -d $SCRIPTPATH)"
helm_package=$(echo $out | cut -d ':' -f 2)
helm_package_name=$(basename $helm_package)

dsttime=0
if [ -f $SCRIPTPATH/../docs/charts/$helm_package_name ]; then
  dsttime="$(stat $mod_time_fmt $SCRIPTPATH/../docs/charts/$helm_package_name)"
fi

if [ $srctime \> $dsttime ];
then
  if [[ ! -e $SCRIPTPATH/../docs/charts ]]; then
    mkdir $SCRIPTPATH/../docs/charts
  fi
  mv -f $helm_package $SCRIPTPATH/../docs/charts/
  helm repo index $SCRIPTPATH/../docs/charts/ --url https://oracle.github.io/weblogic-kubernetes-operator/charts
else
  rm $helm_package
fi;


