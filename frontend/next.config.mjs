// Next.js 설정
// API_PROXY_TARGET(예: https://api.example.com)이 있으면 /api/* 요청을 그 서버로 넘긴다(프록시).
// 브라우저는 같은 도메인으로 요청하므로 CORS·쿠키(SameSite) 문제가 생기지 않는다.
// 값이 없으면 rewrites 를 쓰지 않는다(로컬 목업 모드 등).
const apiProxyTarget = (process.env.API_PROXY_TARGET || '').trim().replace(/\/+$/, '');

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  ...(apiProxyTarget && {
    async rewrites() {
      return [
        { source: '/api/:path*', destination: `${apiProxyTarget}/api/:path*` },
      ];
    },
  }),
};

export default nextConfig;
