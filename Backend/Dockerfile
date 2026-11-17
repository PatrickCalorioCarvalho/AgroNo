FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py upload_fotos.py worker.py ./
EXPOSE 8000
CMD ["python", "server.py", "--port", "8000"]
