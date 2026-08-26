FROM node:20-alpine
WORKDIR /app
COPY frontend/package.json ./
RUN npm install
COPY frontend/ ./
COPY data/ /data/
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "--port", "5173"]
