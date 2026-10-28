#!/usr/bin/env python3
"""Read-only authoring checks for MMR 0.119.0 JSON; not a Forge validator.

Python 3 standard library only. Unknown extension types/options are warnings.
The authoring checks intentionally flag silent mistakes (e.g. missing FE/t),
even when the engine would accept or ignore the field.
"""

import argparse
import json
import math
import re
from decimal import Decimal
from pathlib import Path


INT_MIN, INT_MAX = -2147483648, 2147483647
LONG_MAX = 9223372036854775807
RESOURCE = re.compile(r"[a-z0-9_.-]+:[a-z0-9/._-]+\Z")
BASE = "modular_machinery_reborn"
MAGIC = {"mana", "aura", "lifeessence", "will", "ember"}
COMMON = {"type", "io-type", "selector-tag", "parallelize-unaffected",
          "script-tooltip", "trigger-time", "trigger-repeatable", "chance"}
FIELDS = {
    "item": {"item", "amount", "minAmount", "maxAmount", "nbt", "nbt-display",
             "consume-durability", "fuel-time", "ignore-output-check", "script-checker", "script-modifiers"},
    "fluid": {"fluid", "amount", "perTick"},
    "energy": {"energyPerTick", "perTick"},
    "gas": {"gas", "amount", "perTick"},
    "gas_pertick": {"gas", "amount", "perTick"},
    "mana": {"amount", "perTick", "min", "max"},
    "lifeessence": {"amount", "perTick", "min", "max"},
    "aura": {"amount", "perTick", "aura-type", "min-aura", "max-aura"},
    "will": {"amount", "perTick", "will-type", "min", "max"},
    "ember": {"amount", "ember", "perTick", "min", "max"},
    "compressed_air": {"air", "amount", "pressure", "time", "perTick"},
    "compressed_air_pertick": {"air", "amount", "pressure", "time", "perTick"},
}


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("Non-finite JSON constant: " + value)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"),
                      parse_float=Decimal, object_pairs_hook=no_duplicates,
                      parse_constant=reject_constant)


class Check:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, where, message):
        self.errors.append(f"{where}: {message}")

    def warn(self, where, message):
        self.warnings.append(f"{where}: {message}")

    def text(self, obj, key, where):
        value = obj.get(key)
        if not isinstance(value, str) or not value:
            self.error(where, f"'{key}' must be a non-empty string")
            return None
        return value

    def resource(self, value, where, *, tag=False, bare=False):
        if not isinstance(value, str) or not value:
            self.error(where, "expected a resource ID")
            return None
        if tag and value.startswith("ore:"):
            self.warn(where, "legacy ore name requires engine verification; prefer a modern #tag")
            return value
        normalized = value[1:] if tag and value.startswith("#") else value
        if ":" not in normalized and bare:
            normalized = BASE + ":" + normalized
            self.warn(where, "bare machine name resolves to " + normalized)
        if not RESOURCE.fullmatch(normalized):
            self.error(where, "expected namespace:path" + (" or #namespace:tag" if tag else ""))
            return None
        return normalized

    def number(self, obj, key, where, default=None, minimum=0, maximum=INT_MAX, integer=True):
        value = obj.get(key, default)
        valid = not isinstance(value, bool) and isinstance(value, (int, Decimal))
        if valid:
            try:
                valid = math.isfinite(float(value)) and (not integer or value == int(value))
            except (OverflowError, ValueError):
                valid = False
        if not valid or (minimum is not None and value < minimum) or (maximum is not None and value > maximum):
            kind = "integer" if integer else "finite number"
            self.error(where, f"'{key}' must be a {kind} in {minimum}..{maximum}")
            return None
        return value

    def boolean(self, obj, key, where):
        if key in obj and not isinstance(obj[key], bool):
            self.error(where, f"'{key}' must be true or false")

    def requirement(self, req, where):
        if not isinstance(req, dict):
            self.error(where, "requirement must be an object")
            return
        raw = self.text(req, "type", where)
        kind = raw.rsplit(":", 1)[-1] if raw else ""
        io = self.text(req, "io-type", where)
        io = io.lower() if io else None
        if io and io not in ("input", "output"):
            self.error(where, "io-type must be input or output")
        for key in ("perTick", "parallelize-unaffected", "trigger-repeatable"):
            self.boolean(req, key, where)
        for key in ("selector-tag", "script-tooltip"):
            if key in req and not isinstance(req[key], str):
                self.error(where, f"'{key}' must be a string")
        if "chance" in req:
            self.number(req, "chance", where, maximum=1, integer=False)
        per_tick = kind in ("energy", "gas_pertick", "compressed_air_pertick") or req.get("perTick") is True
        if "trigger-time" in req:
            self.number(req, "trigger-time", where)
            if per_tick:
                self.error(where, "trigger-time cannot be combined with energy or per-tick requirements")
        if kind in ("fluid_pertick", "item_durability", "duration"):
            self.error(where, f"'{kind}' is not a built-in requirement type in this version")
        elif kind == "item":
            self.resource(req.get("item"), where + ".item", tag=True)
            amount = self.number(req, "amount", where, default=1)
            low = self.number(req, "minAmount", where, default=amount)
            high = self.number(req, "maxAmount", where, default=amount)
            if low is not None and high is not None:
                if low > high:
                    self.error(where, "minAmount must not exceed maxAmount")
                if io == "input" and low != high:
                    self.error(where, "item inputs must have a fixed amount")
            if req.get("perTick") is True:
                self.error(where, "item perTick is unsupported; this field would not make it per-tick")
            for key in ("nbt", "nbt-display"):
                if key in req:
                    if not isinstance(req[key], dict):
                        self.error(where, f"'{key}' must be an object")
                    else:
                        self.warn(where, f"'{key}' contents need NBT predicate/type verification")
            if "nbt-display" in req and "nbt" not in req:
                self.warn(where, "nbt-display without nbt is ignored by the JSON item parser")
            if io == "output" and isinstance(req.get("item"), str) and req["item"].startswith("#"):
                self.warn(where, "tag output depends on the first resolved item; prefer a concrete ID")
        elif kind in ("fluid", "gas", "gas_pertick"):
            field = "fluid" if kind == "fluid" else "gas"
            self.resource(req.get(field), where + "." + field)
            self.number(req, "amount", where, default=1 if kind == "fluid" else None, minimum=1)
            if kind.startswith("gas"):
                self.warn(where, "requires Mekanism and a registered gas; gas kind controls tick behavior")
                if "perTick" in req and req["perTick"] != (kind == "gas_pertick"):
                    self.error(where, "perTick contradicts gas/gas_pertick; choose the correct type")
        elif kind == "energy":
            self.number(req, "energyPerTick", where, maximum=LONG_MAX)
            if "amount" in req or req.get("perTick") is False:
                self.error(where, "energy uses energyPerTick (FE/t) and is always per-tick")
        elif kind in ("compressed_air", "compressed_air_pertick"):
            field = "air" if "air" in req else "amount"
            self.number(req, field, where, default=0 if io == "input" else None)
            if io == "input" or "pressure" in req:
                self.number(req, "pressure", where, minimum=None, maximum=None, integer=False)
            if "perTick" in req and req["perTick"] != (kind == "compressed_air_pertick"):
                self.error(where, "perTick contradicts compressed_air/compressed_air_pertick")
            if "time" in req:
                self.number(req, "time", where, minimum=1)
                self.warn(where, "legacy time does not set runtime duration; use root recipeTime")
            self.warn(where, "requires PneumaticCraft, a formed air hatch, and sufficient actual pressure")
        elif kind in MAGIC:
            fractional = kind in ("will", "ember")
            amount_key = "ember" if kind == "ember" and "amount" not in req else "amount"
            self.number(req, amount_key, where, maximum=None if fractional else INT_MAX, integer=not fractional)
            is_aura = kind == "aura"
            low_key, high_key = ("min-aura", "max-aura") if is_aura else ("min", "max")
            low = self.number(req, low_key, where, default=INT_MIN if is_aura else 0,
                              minimum=None if fractional else INT_MIN,
                              maximum=None if fractional else INT_MAX, integer=not fractional)
            high = self.number(req, high_key, where, default=100 if kind == "will" else Decimal('1.7976931348623157e308') if kind == "ember" else INT_MAX,
                               minimum=None if fractional else INT_MIN,
                               maximum=None if fractional else INT_MAX, integer=not fractional)
            if low is not None and high is not None and low > high:
                self.error(where, "resource minimum must not exceed maximum")
            if is_aura:
                self.resource(req.get("aura-type"), where + ".aura-type")
                if io == "input" and "min-aura" not in req:
                    self.warn(where, "default min-aura allows negative environmental Aura; use 0 to prevent this")
            if kind == "will":
                variant = req.get("will-type", "default")
                if not isinstance(variant, str) or variant.lower() not in ("default", "corrosive", "destructive", "vengeful", "steadfast"):
                    self.error(where, "unknown will-type")
            self.warn(where, "requires the matching optional mod, hatch, and real resource access")
        else:
            self.warn(where, f"'{kind}' has no type-specific checks here; verify its current parser/registration")
        if kind in FIELDS:
            extra = sorted(set(req) - COMMON - FIELDS[kind])
            if extra:
                self.warn(where, "unverified fields (possibly ignored): " + ", ".join(extra))
        script_keys = {"script-checker", "script-modifiers", "consume-durability", "fuel-time", "ignore-output-check"}
        if set(req) & script_keys:
            self.warn(where, "advanced script options need current engine verification")

    def recipe(self, obj, machine=None):
        if not isinstance(obj, dict):
            self.error("root", "recipe must be an object")
            return
        if obj.get("type") != BASE + ":machine":
            self.error("root", "type must be modular_machinery_reborn:machine")
        owners = obj.get("machine")
        owners = owners if isinstance(owners, list) else [owners]
        if not owners:
            self.error("root.machine", "machine list must not be empty")
        normalized = [self.resource(owner, "root.machine", bare=True) for owner in owners]
        if machine is not None:
            if not isinstance(machine, dict):
                self.error("machine file", "must be an object")
            else:
                target = self.resource(machine.get("registryname"), "machine.registryname", bare=True)
                if target and target not in normalized:
                    self.error("root.machine", "provided machine definition does not own this recipe: " + target)
        if "registryname" in obj:
            self.error("root", "registryname belongs to machine definitions; recipe name uses registryName")
        if "registryName" in obj:
            self.text(obj, "registryName", "root")
        self.number(obj, "recipeTime", "root", default=100, minimum=1)
        self.number(obj, "max-parallelism", "root", default=0)
        self.number(obj, "priority", "root", default=0, minimum=INT_MIN)
        self.boolean(obj, "cancelIfPerTickFails", "root")
        if "tooltips" in obj:
            tips = obj["tooltips"]
            if not isinstance(tips, str) and not (isinstance(tips, list) and all(isinstance(t, str) for t in tips)):
                self.error("root", "tooltips must be a string or string array")
        adapter = "adapter" in obj
        if adapter:
            self.resource(obj["adapter"], "root.adapter")
            self.warn("root", "adapter declaration generation/modifiers are not validated here")
        elif "modifiers" in obj:
            self.warn("root", "root modifiers belong to adapter declarations; not generic recipe speed modifiers")
        requirements = obj.get("requirements", [] if adapter else None)
        if not isinstance(requirements, list) or (not requirements and not adapter):
            self.error("root", "ordinary recipes need a non-empty requirements array")
        else:
            for index, req in enumerate(requirements):
                self.requirement(req, f"requirements[{index}]")
        known = {"type", "machine", "registryName", "recipeTime", "max-parallelism", "priority",
                 "cancelIfPerTickFails", "tooltips", "requirements", "adapter", "modifiers"}
        extra = sorted(set(obj) - known)
        if extra:
            self.warn("root", "unverified root fields (possibly ignored): " + ", ".join(extra))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recipes", nargs="+", type=Path)
    parser.add_argument("--machine", type=Path, help="optional machine JSON; only its registryname is checked")
    args = parser.parse_args()
    try:
        machine = read_json(args.machine) if args.machine else None
        if args.machine and not isinstance(machine, dict):
            raise ValueError("machine JSON must be an object")
    except (OSError, ValueError) as error:
        print(f"ERROR machine file: {error}")
        return 1
    errors = warnings = 0
    for path in args.recipes:
        check = Check()
        try:
            check.recipe(read_json(path), machine)
        except (OSError, ValueError) as error:
            check.error("file", str(error))
        print(str(path))
        for message in check.errors:
            print("  ERROR " + message)
        for message in check.warnings:
            print("  WARN  " + message)
        if not check.errors:
            print("  PASS covered static checks only")
        errors += len(check.errors)
        warnings += len(check.warnings)
    print(f"{errors} error(s), {warnings} warning(s). Registries, scripts and gameplay were not validated.")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
