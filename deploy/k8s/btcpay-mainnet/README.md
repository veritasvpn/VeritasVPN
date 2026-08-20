# Isolated BTCPay mainnet environment

This directory provisions a second, isolated BTCPay Server environment for
Bitcoin **mainnet**. It does not modify the existing \`btcpay\` namespace, which
continues to serve Bitcoin testnet.

## Layout

- Namespace: \`btcpay-mainnet\`
- Public hostname to configure: \`btcpay-mainnet.veritasvpn.cloud\`
- BTCPay service: \`btcpayserver-mainnet:49392\`
- Bitcoin daemon: \`bitcoind-mainnet\` (\`mainnet\`, pruned to 10 GiB)
- Dedicated PostgreSQL and NBXplorer data stores

\`secrets.yaml\` is generated on the Dell and intentionally excluded from Git.
Never copy wallet seeds, API keys, database passwords, or webhook secrets into
this directory or the repository.

## First-use checklist

1. In Cloudflare Tunnel, add the published hostname
   \`btcpay-mainnet.veritasvpn.cloud\` with service URL
   \`http://btcpayserver-mainnet.btcpay-mainnet.svc.cluster.local:49392\`.
2. Wait for Bitcoin Core to finish initial block download:
   \`kubectl -n btcpay-mainnet exec bitcoind-mainnet-0 -- bitcoin-cli -conf=/etc/bitcoin-rpc/bitcoin.conf getblockchaininfo\`
   must report \`"initialblockdownload": false\`.
3. Open the new hostname, create the BTCPay administrator account, and create
   a store named \`VeritasVPN Mainnet\`.
4. Connect a real mainnet wallet under your own control. Keep its seed offline;
   never paste it into this repository or chat.
5. Create a mainnet API key scoped to that store with invoice-create and
   invoice-view permissions, then configure the billing service during a
   deliberate production cutover.

## Deployment validation

\`\`\`sh
kubectl kustomize deploy/k8s/btcpay-mainnet >/dev/null
kubectl -n btcpay-mainnet get pods,pvc
\`\`\`

The mainnet environment is deliberately separate. Do not repoint the existing
\`btcpay.veritasvpn.cloud\` testnet hostname or app billing configuration until
the mainnet node is synchronized, the wallet is configured, and a controlled
cutover has been approved.
