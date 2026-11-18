from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

router = APIRouter(tags=["health"])


@router.get("/health")
def health(request: Request) -> JSONResponse:
    try:
        request.app.state.ctx.db.ping()
    except Exception:
        return JSONResponse({"status": "error"}, status_code=503)
    return JSONResponse({"status": "ok"}, status_code=200)
