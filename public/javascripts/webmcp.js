(function () {
  if (!navigator.modelContext) return;

  var mc = navigator.modelContext;

  // registerTool is the current WebMCP draft API; addTool was used by
  // earlier polyfills.
  var register = mc.registerTool
    ? mc.registerTool.bind(mc)
    : mc.addTool
      ? mc.addTool.bind(mc)
      : null;
  if (!register) return;

  function addTool(tool) {
    // "execute" is the current draft field, "handler" was used by earlier
    // polyfills; supply both.
    tool.execute = tool.execute || tool.handler;
    tool.handler = tool.handler || tool.execute;
    register(tool);
  }

  addTool({
    name: "create_op_return",
    description:
      "Create a Lightning invoice to write an OP_RETURN message on the Bitcoin blockchain",
    inputSchema: {
      type: "object",
      properties: {
        message: {
          type: "string",
          description: "The message to write (max 99,000 bytes)",
        },
        noTwitter: {
          type: "boolean",
          description: "If true, do not post to Twitter",
          default: false,
        },
      },
      required: ["message"],
    },
    execute: function (args) {
      var body = new URLSearchParams();
      body.append("message", args.message);
      if (args.noTwitter) body.append("noTwitter", "true");
      return fetch("/api/create", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: body.toString(),
      })
        .then(function (r) {
          return r.text();
        })
        .then(function (invoice) {
          return { content: [{ type: "text", text: invoice }] };
        });
    },
  });

  addTool({
    name: "create_unified_payment",
    description:
      "Create a unified payment (Lightning + on-chain) for an OP_RETURN message",
    inputSchema: {
      type: "object",
      properties: {
        message: {
          type: "string",
          description: "The message to write (max 99,000 bytes)",
        },
        noTwitter: {
          type: "boolean",
          description: "If true, do not post to Twitter",
          default: false,
        },
      },
      required: ["message"],
    },
    execute: function (args) {
      var body = new URLSearchParams();
      body.append("message", args.message);
      if (args.noTwitter) body.append("noTwitter", "true");
      return fetch("/api/unified", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: body.toString(),
      })
        .then(function (r) {
          return r.json();
        })
        .then(function (data) {
          return {
            content: [{ type: "text", text: JSON.stringify(data) }],
          };
        });
    },
  });

  addTool({
    name: "check_payment_status",
    description:
      "Check the payment and broadcast status of an OP_RETURN request",
    inputSchema: {
      type: "object",
      properties: {
        rHash: {
          type: "string",
          description: "The payment hash (r_hash) in hex",
        },
      },
      required: ["rHash"],
    },
    execute: function (args) {
      return fetch("/api/status/" + encodeURIComponent(args.rHash))
        .then(function (r) {
          return r.text();
        })
        .then(function (text) {
          return { content: [{ type: "text", text: text }] };
        });
    },
  });

  addTool({
    name: "view_message",
    description:
      "View the OP_RETURN message for a confirmed transaction",
    inputSchema: {
      type: "object",
      properties: {
        txId: {
          type: "string",
          description: "The transaction ID in hex",
        },
      },
      required: ["txId"],
    },
    execute: function (args) {
      return fetch("/api/view/" + encodeURIComponent(args.txId))
        .then(function (r) {
          return r.text();
        })
        .then(function (text) {
          return { content: [{ type: "text", text: text }] };
        });
    },
  });
})();
