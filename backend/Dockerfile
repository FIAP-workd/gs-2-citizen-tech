FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /srv
COPY pyproject.toml ./
COPY app ./app
# /srv/data recebe o volume do banco; precisa pertencer ao usuário sem privilégios.
RUN pip install --no-cache-dir . \
    && useradd --create-home --uid 10001 relay \
    && mkdir -p /srv/data && chown relay /srv/data

USER relay
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
