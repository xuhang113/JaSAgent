#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <stdint.h>

static int (*real_connect)(int, const struct sockaddr *, socklen_t);

static int allowed_v4(uint32_t host_order_ip)
{
	if ((host_order_ip & 0xffff0000u) == 0x0a2a0000u) /* 10.42.0.0/16 */
		return 1;
	if ((host_order_ip & 0xffff0000u) == 0x0a2b0000u) /* 10.43.0.0/16 */
		return 1;
	if ((host_order_ip & 0xff000000u) == 0x7f000000u) /* 127.0.0.0/8 */
		return 1;
	return 0;
}

int connect(int fd, const struct sockaddr *addr, socklen_t len)
{
	if (!real_connect)
		real_connect = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "connect");
	if (addr && addr->sa_family == AF_INET && len >= (socklen_t)sizeof(struct sockaddr_in)) {
		const struct sockaddr_in *in = (const struct sockaddr_in *)addr;
		uint32_t ip = ntohl(in->sin_addr.s_addr);
		if (!allowed_v4(ip)) {
			errno = EHOSTUNREACH;
			return -1;
		}
	}
	if (addr && addr->sa_family == AF_INET6 && len >= (socklen_t)sizeof(struct sockaddr_in6)) {
		const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *)addr;
		const unsigned char *p = in6->sin6_addr.s6_addr;
		/* ::1 */
		int loop = 1;
		for (int i = 0; i < 15; i++) if (p[i] != 0) loop = 0;
		if (!(loop && p[15] == 1) && !((p[0] & 0xfe) == 0xfc) && !(p[0] == 0xfe && (p[1] & 0xc0) == 0x80)) {
			/* also allow v4-mapped 10.42/10.43/127 */
			int v4mapped = 1;
			for (int i = 0; i < 10; i++) if (p[i] != 0) v4mapped = 0;
			if (!(v4mapped && p[10] == 0xff && p[11] == 0xff &&
			      allowed_v4(((uint32_t)p[12] << 24) | ((uint32_t)p[13] << 16) | ((uint32_t)p[14] << 8) | p[15]))) {
				errno = EHOSTUNREACH;
				return -1;
			}
		}
	}
	return real_connect(fd, addr, len);
}
