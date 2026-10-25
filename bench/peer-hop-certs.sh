#!/bin/sh
# #236 PeerHopBench: a throwaway CA and two node certificates with the default pinned URI SANs.
set -eu
d=${1:?cert dir}
mkdir -p "$d" && cd "$d"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca.key -out ca.pem -days 2 \
  -subj /CN=peer-hop-bench-ca -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign 2>/dev/null
for n in a b; do
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$n.key" -out "$n.csr" -subj "/CN=$n" 2>/dev/null
  printf 'subjectAltName=URI:urn:sjws:node:%s\nextendedKeyUsage=serverAuth,clientAuth\nkeyUsage=critical,digitalSignature\n' "$n" > "$n.ext"
  openssl x509 -req -in "$n.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -out "$n.pem" -extfile "$n.ext" 2>/dev/null
  chmod 600 "$n.key"
done
rm -f ./*.csr ./*.ext ca.srl
