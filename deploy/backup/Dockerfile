# pg_dump hằng ngày → Cloudflare R2 (bucket riêng cho backup), giữ BACKUP_KEEP_DAYS ngày.
FROM postgres:16-alpine
RUN apk add --no-cache rclone tzdata
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh
ENTRYPOINT ["/usr/local/bin/backup.sh"]
