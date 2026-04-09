FROM nginx:alpine

# Vite production bundle — built by bin/build-presentation.sh before docker build
COPY dist /usr/share/nginx/html

# SPA routing: all unmatched paths fall back to index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
