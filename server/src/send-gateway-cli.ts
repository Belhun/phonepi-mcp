#!/usr/bin/env node
/** Entry point: node dist/send-gateway-cli.js  (127.0.0.1:11044 by default). */
import { DEFAULT_GATEWAY_PORT, SendGateway, defaultGatewayPaths, httpBridgeClient, startSendGateway } from "./send-gateway.js";

const port = Number(process.env.PHONEPI_SEND_GATEWAY_PORT || DEFAULT_GATEWAY_PORT);
const paths = defaultGatewayPaths();
const gw = new SendGateway(httpBridgeClient(paths.bridgeKeyFile), paths);
startSendGateway(gw, port)
  .then(() => console.log(`phonepi-send-gateway listening on 127.0.0.1:${port}`))
  .catch((err) => {
    console.error(`phonepi-send-gateway failed to start: ${err.message}`);
    process.exit(1);
  });
