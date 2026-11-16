"""Erros do contrato de mensagens (docs/message-contract.md §6)."""

from __future__ import annotations


class RelayError(Exception):
    def __init__(self, code: str, http_status: int = 400) -> None:
        super().__init__(code)
        self.code = code
        self.http_status = http_status
