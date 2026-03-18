(function () {
  if (!navigator.modelContext) return;

  var mc = navigator.modelContext;

  mc.addTool({
    name: "create_op_return",
    description:
      "Create a Lightning invoice to write an OP_RETURN message on the Bitcoin blockchain",
    inputSchema: {
      type: "object",
      properties: {
        message: {
          type: "string",
          description: "The message to write (max 80 bytes)",
        },
        noTwitter: {
          type: "boolean",
          description: "If true, do not post to Twitter",
          default: false,
        },
      },
      required: ["message"],
    },
    handler: function (args) {
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

  mc.addTool({
    name: "create_unified_payment",
    description:
      "Create a unified payment (Lightning + on-chain) for an OP_RETURN message",
    inputSchema: {
      type: "object",
      properties: {
        message: {
          type: "string",
          description: "The message to write (max 80 bytes)",
        },
        noTwitter: {
          type: "boolean",
          description: "If true, do not post to Twitter",
          default: false,
        },
      },
      required: ["message"],
    },
    handler: function (args) {
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

  mc.addTool({
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
    handler: function (args) {
      return fetch("/api/status/" + encodeURIComponent(args.rHash))
        .then(function (r) {
          return r.text();
        })
        .then(function (text) {
          return { content: [{ type: "text", text: text }] };
        });
    },
  });

  mc.addTool({
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
    handler: function (args) {
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
