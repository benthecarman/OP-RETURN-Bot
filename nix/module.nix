{ self }:
{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.op-return-bot;
  configPath =
    if cfg.configText != null then
      toString (pkgs.writeText "op-return-bot.conf" cfg.configText)
    else if cfg.configFile != null then
      cfg.configFile
    else
      "/dev/null";
in
{
  options.services.op-return-bot = {
    enable = lib.mkEnableOption "the OP_RETURN Bot service";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.op-return-bot;
      defaultText = lib.literalExpression "inputs.op-return-bot.packages.${pkgs.system}.default";
      description = "The OP_RETURN Bot package to run.";
    };

    configFile = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "/run/secrets/op-return-bot.conf";
      description = ''
        The absolute path to the HOCON configuration file.
        Use a runtime secret path to keep credentials out of the Nix store.
      '';
    };

    configText = lib.mkOption {
      type = lib.types.nullOr lib.types.lines;
      default = null;
      example = ''
        include classpath("application.conf")

        bitcoin-s.network = mainnet
      '';
      description = ''
        The HOCON configuration that Nix writes to the Nix store.
        Do not use this option for private credentials.
      '';
    };

    address = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = "The address on which the HTTP server listens.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 9000;
      description = "The port on which the HTTP server listens.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "This option opens the HTTP port in the host firewall.";
    };

    user = lib.mkOption {
      type = lib.types.str;
      default = "op-return-bot";
      description = "The user account for the service.";
    };

    group = lib.mkOption {
      type = lib.types.str;
      default = "op-return-bot";
      description = "The group account for the service.";
    };

    dataDir = lib.mkOption {
      type = lib.types.str;
      default = "/var/lib/op-return-bot";
      description = "The directory for the database, seed, and service state.";
    };

    extraJavaOptions = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
      example = [ "-Xmx1g" ];
      description = "Additional options for the Java process.";
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = (cfg.configFile != null) != (cfg.configText != null);
        message = ''
          Set exactly one of services.op-return-bot.configFile or
          services.op-return-bot.configText.
        '';
      }
      {
        assertion = cfg.configFile == null || lib.hasPrefix "/" cfg.configFile;
        message = "services.op-return-bot.configFile must be an absolute path.";
      }
      {
        assertion = lib.hasPrefix "/" cfg.dataDir;
        message = "services.op-return-bot.dataDir must be an absolute path.";
      }
    ];

    users.users = lib.mkIf (cfg.user == "op-return-bot") {
      op-return-bot = {
        isSystemUser = true;
        inherit (cfg) group;
        home = cfg.dataDir;
      };
    };

    users.groups = lib.mkIf (cfg.group == "op-return-bot") {
      op-return-bot = { };
    };

    networking.firewall.allowedTCPPorts = lib.optionals cfg.openFirewall [ cfg.port ];

    systemd.services.op-return-bot = {
      description = "OP_RETURN Bot";
      wantedBy = [ "multi-user.target" ];
      wants = [ "network-online.target" ];
      after = [ "network-online.target" ];
      unitConfig.ConditionPathExists = configPath;

      serviceConfig = {
        Type = "simple";
        User = cfg.user;
        Group = cfg.group;
        WorkingDirectory = cfg.dataDir;
        RuntimeDirectory = "op-return-bot";
        Environment = [
          "HOME=${cfg.dataDir}"
          "JAVA_OPTS=${lib.concatStringsSep " " cfg.extraJavaOptions}"
        ];
        ExecStart = lib.escapeShellArgs ([
          (lib.getExe cfg.package)
          "-Dconfig.file=${configPath}"
          "-Dhttp.address=${cfg.address}"
          "-Dhttp.port=${toString cfg.port}"
          "-Dpidfile.path=/run/op-return-bot/play.pid"
        ]);
        Restart = "on-failure";
        RestartSec = 5;
        TimeoutStopSec = 30;
        UMask = "0077";

        CapabilityBoundingSet = "";
        LockPersonality = true;
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectHome = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        ProtectSystem = "strict";
        RestrictAddressFamilies = [
          "AF_INET"
          "AF_INET6"
          "AF_UNIX"
        ];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        SystemCallArchitectures = "native";
      }
      // lib.optionalAttrs (cfg.dataDir == "/var/lib/op-return-bot") {
        StateDirectory = "op-return-bot";
      }
      // lib.optionalAttrs (cfg.dataDir != "/var/lib/op-return-bot") {
        ReadWritePaths = [ cfg.dataDir ];
      };
    };

    systemd.tmpfiles.rules = lib.optionals (cfg.dataDir != "/var/lib/op-return-bot") [
      "d ${cfg.dataDir} 0700 ${cfg.user} ${cfg.group} -"
    ];
  };
}
