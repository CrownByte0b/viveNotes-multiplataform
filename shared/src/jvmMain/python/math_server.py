"""The desktop's stand-in for Chaquopy: ``math_engine`` behind a pipe.

On Android, Kotlin calls ``math_engine`` in-process through Chaquopy. The desktop runs this script
in a uv-managed CPython instead and talks to it over the process's standard streams: one JSON
request per line in, one JSON reply per line out.

    {"id": 7, "call": "analyze", "args": ["x^2-4=0"]}
    {"id": 7, "result": "{\\"normalizedLatex\\":...}"}

``result`` is the string the call returned, untouched — the same text Android's
``callAttr(...).toString()`` hands Kotlin to decode — or ``null`` when it returned ``None``. A request
that cannot be served gets ``{"id": ..., "error": "..."}`` instead.

Only the calls in ``_CALLS`` can be reached, with string arguments. Nothing read here is evaluated as
Python, which keeps the boundary ``math_engine`` describes: LaTeX and action ids in, JSON out.
"""

import json
import platform
import sys
import traceback
from typing import Any, Callable

# The replies own stdout. Anything else that prints — a library warning, a stray debug line — goes
# to stderr, where it cannot split a reply in two.
_replies = sys.stdout
sys.stdout = sys.stderr

import sympy  # noqa: E402

import math_engine  # noqa: E402


def _versions() -> str:
    return json.dumps({"python": platform.python_version(), "sympy": sympy.__version__})


#: Each call, and the fewest and most string arguments it takes (``None``: no upper bound).
_CALLS: dict[str, tuple[Callable[..., Any], int, int | None]] = {
    "analyze": (math_engine.analyze, 1, 1),
    "execute": (math_engine.execute, 2, 2),
    "calculate": (math_engine.calculate, 1, None),
    "warm_up": (math_engine.warm_up, 0, 0),
    "versions": (_versions, 0, 0),
}


def _reply(payload: dict[str, Any]) -> None:
    _replies.write(json.dumps(payload, ensure_ascii=False) + "\n")
    _replies.flush()


def _serve(line: str) -> dict[str, Any]:
    try:
        request = json.loads(line)
    except ValueError:
        return {"id": None, "error": "The request is not JSON."}
    if not isinstance(request, dict):
        return {"id": None, "error": "The request is not an object."}
    request_id = request.get("id")
    if not isinstance(request_id, int) or isinstance(request_id, bool):
        return {"id": None, "error": "The request has no id."}

    call = _CALLS.get(request.get("call"))
    if call is None:
        return {"id": request_id, "error": "Unknown call."}
    function, fewest, most = call
    args = request.get("args", [])
    if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
        return {"id": request_id, "error": "Arguments must be strings."}
    if len(args) < fewest or (most is not None and len(args) > most):
        return {"id": request_id, "error": "Wrong number of arguments."}

    try:
        result = function(*args)
    except Exception:  # math_engine reports expected failures itself; this is everything else.
        traceback.print_exc()
        return {"id": request_id, "error": "The math engine failed."}
    if result is not None and not isinstance(result, str):
        return {"id": request_id, "error": "The math engine returned an unexpected value."}
    return {"id": request_id, "result": result}


def main() -> None:
    for line in sys.stdin:
        if line.strip():
            _reply(_serve(line))


if __name__ == "__main__":
    main()
