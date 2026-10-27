FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY na-assistant-service/requirements.txt ./requirements.txt
RUN pip install -r requirements.txt

COPY na-assistant-service/app ./app

RUN useradd --system --create-home --uid 10001 assistant
USER assistant

EXPOSE 8092
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8092", "--no-access-log"]
