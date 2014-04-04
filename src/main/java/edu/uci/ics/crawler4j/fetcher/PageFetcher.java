/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.fetcher;

import edu.uci.ics.crawler4j.crawler.Configurable;
import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.url.URLCanonicalizer;
import edu.uci.ics.crawler4j.url.WebURL;
import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParamBean;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * @author Yasser Ganjisaffar <lastname at gmail dot com>
 */
public class PageFetcher extends Configurable {

	protected static final Logger logger = LoggerFactory.getLogger(PageFetcher.class);

	protected PoolingClientConnectionManager connectionManager;

	protected DefaultHttpClient httpClient;

	protected IdleConnectionMonitorThread connectionMonitorThread = null;

	public PageFetcher(CrawlConfig config) {
		super(config);

		HttpParams params = new BasicHttpParams();
		HttpProtocolParamBean paramsBean = new HttpProtocolParamBean(params);
		paramsBean.setVersion(HttpVersion.HTTP_1_1);
		paramsBean.setContentCharset("UTF-8");
		paramsBean.setUseExpectContinue(false);

		params.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);
		params.setParameter(CoreProtocolPNames.USER_AGENT, config.getUserAgentString());
		params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, config.getSocketTimeout());
		params.setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, config.getConnectionTimeout());

        // FIX for #136 - JVM crash while running crawler on Cent OS 6.2 - http://code.google.com/p/crawler4j/issues/detail?id=136
        params.setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);
		params.setBooleanParameter("http.protocol.handle-redirects", false);

		connectionManager = new PoolingClientConnectionManager();
		connectionManager.setMaxTotal(config.getMaxTotalConnections());
		connectionManager.setDefaultMaxPerRoute(config.getMaxConnectionsPerHost());
		httpClient = new DefaultHttpClient(connectionManager, params);

		if (config.getProxyHost() != null) {
			if (config.getProxyUsername() != null) {
				httpClient.getCredentialsProvider().setCredentials(
						new AuthScope(config.getProxyHost(), config.getProxyPort()),
						new UsernamePasswordCredentials(config.getProxyUsername(), config.getProxyPassword()));
			}

			HttpHost proxy = new HttpHost(config.getProxyHost(), config.getProxyPort());
			httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        }

        httpClient.addResponseInterceptor(new HttpResponseInterceptor() {

            @Override
            public void process(final HttpResponse response, final HttpContext context) throws HttpException,
                    IOException {
                HttpEntity entity = response.getEntity();
                if (entity == null) return;

                Header contentEncoding = entity.getContentEncoding();
                if (contentEncoding != null) {
                    HeaderElement[] codecs = contentEncoding.getElements();
                    for (HeaderElement codec : codecs) {
                        if (codec.getName().equalsIgnoreCase("gzip")) {
                            response.setEntity(new GzipDecompressingEntity(response.getEntity()));
                            return;
                        }
                    }
                }
            }

        });

		if (connectionMonitorThread == null) {
			connectionMonitorThread = new IdleConnectionMonitorThread(connectionManager);
		}
		connectionMonitorThread.start();

	}

	public PageFetchResult fetchHeader(WebURL webUrl) {
		PageFetchResult fetchResult = new PageFetchResult();
		String toFetchURL = webUrl.getURL();
		HttpGet get = null;

        String proxyConnection = config.getProxyHost() != null ? ", Via-Proxy" : ", Direct-Crawl";

		try {
			get = new HttpGet(toFetchURL);
			get.addHeader("Accept-Encoding", "gzip");
            get.addHeader("Accept", "*/*");

            for (Map.Entry<String, String> entry : config.getCustomHeaders().entrySet()) {
                get.addHeader(entry.getKey(), entry.getKey());
            }

            // Create a local instance of cookie store, and bind to local context
            // Without this we get killed w/lots of threads, due to sync() on single cookie store.
            HttpContext localContext = new BasicHttpContext();
            CookieStore cookieStore = new BasicCookieStore();
            for (Map.Entry<String, String> entry : config.getCustomCookies().entrySet()) {
                cookieStore.addCookie(new BasicClientCookie(entry.getKey(), entry.getValue()));
            }
            localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);

			HttpResponse response = httpClient.execute(get);
			fetchResult.setEntity(response.getEntity());
			fetchResult.setResponseHeaders(response.getAllHeaders());
			
			int statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != HttpStatus.SC_OK) {
				if (statusCode != HttpStatus.SC_NOT_FOUND) {
					if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY || statusCode == HttpStatus.SC_SEE_OTHER) {
						Header header = response.getFirstHeader("Location");
						if (header != null) {
							String movedToUrl = header.getValue();
							movedToUrl = URLCanonicalizer.getCanonicalURL(movedToUrl, toFetchURL);
							fetchResult.setMovedToUrl(movedToUrl);
						} 
						fetchResult.setStatusCode(statusCode);
						return fetchResult;
					}
					logger.info("Failed: " + response.getStatusLine().toString() + ", while fetching " + toFetchURL + proxyConnection);
				}
				fetchResult.setStatusCode(response.getStatusLine().getStatusCode());
				return fetchResult;
			}

			fetchResult.setFetchedUrl(toFetchURL);
			String uri = get.getURI().toString();
			if (!uri.equals(toFetchURL)) {
				if (!URLCanonicalizer.getCanonicalURL(uri).equals(toFetchURL)) {
					fetchResult.setFetchedUrl(uri);
				}
			}

			if (fetchResult.getEntity() != null) {
				long size = fetchResult.getEntity().getContentLength();
				if (size == -1) {
					Header length = response.getLastHeader("Content-Length");
					if (length == null) {
						length = response.getLastHeader("Content-length");
					}
					if (length != null) {
						size = Integer.parseInt(length.getValue());
					} else {
						size = -1;
					}
				}

				if (size > config.getMaxDownloadSize()) {
					fetchResult.setStatusCode(CustomFetchStatus.PageTooBig);
					get.abort();
					logger.error("Failed: Page Size (" + size + ") exceeded max-download-size (" + config.getMaxDownloadSize() + ")" + proxyConnection);
					return fetchResult;
				}

				fetchResult.setStatusCode(HttpStatus.SC_OK);
				return fetchResult;

            } else {
               logger.error("Failed: Fetched HttpEntity Null " + webUrl.getURL() + proxyConnection);
            }

			get.abort();
			
		} catch (IOException e) {
			logger.error("Fatal transport error: " + e.getMessage() + " while fetching " + toFetchURL
                       + " (link found in doc #" + webUrl.getParentDocid() + ")" + proxyConnection);
			fetchResult.setStatusCode(CustomFetchStatus.FatalTransportError);
			return fetchResult;
		} catch (Exception e) {
			if (e.getMessage() == null) {
				logger.error("Error while fetching " + webUrl.getURL() + proxyConnection, e);
			} else {
				logger.error(e.getMessage() + " while fetching " + webUrl.getURL() + proxyConnection);
			}
		} finally {
			try {
				if (fetchResult.getEntity() == null && get != null) {
					get.abort();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		fetchResult.setStatusCode(CustomFetchStatus.UnknownError);
		logger.error("Failed: Unknown error occurred while fetching " + webUrl.getURL() + proxyConnection);
		return fetchResult;
	}

	public synchronized void shutDown() {
		if (connectionMonitorThread != null) {
			connectionManager.shutdown();
			connectionMonitorThread.shutdown();
		}
	}
	
	public HttpClient getHttpClient() {
		return httpClient;
	}

	private static class GzipDecompressingEntity extends HttpEntityWrapper {

		public GzipDecompressingEntity(final HttpEntity entity) {
			super(entity);
		}

		@Override
		public InputStream getContent() throws IOException, IllegalStateException {

			// the wrapped entity's getContent() decides about repeatability
			InputStream wrappedin = wrappedEntity.getContent();

			return new GZIPInputStream(wrappedin);
		}

		@Override
		public long getContentLength() {
			// length of ungzipped content is not known
			return -1;
		}

	}
}
