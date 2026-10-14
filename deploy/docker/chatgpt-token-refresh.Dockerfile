FROM python:3.12-slim-bookworm
WORKDIR /app
COPY deploy/chatgpt-plan/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --disable-pip-version-check -r /app/requirements.txt
COPY scripts/chatgpt_plan_token_refresh.py /app/chatgpt_plan_token_refresh.py
USER 10001:10001
ENTRYPOINT ["python", "/app/chatgpt_plan_token_refresh.py"]
