const cheerio = require('cheerio');
const fileType = require('file-type');
const fs = require('fs');
const got = require('got');
const mime = require('mime-types');
const path = require('path');
const piscina = require('piscina');
const websiteScraper = require('website-scraper');
const bySiteStructureFilenameGenerator = require('website-scraper/lib/filename-generator/by-site-structure');
const log = require('why-is-node-running')
const typeExtensions = require('website-scraper/lib/config/resource-ext-by-type');

const config = require('../config');
const logger = require('../logger');
const utils = require('../utils');

const { getUrlRegExp } = utils;

// Initialize worker thread pool
const pool = new piscina({
    filename: __dirname + '/scraper-worker.js',
    minThreads: config.threadPoolSize,
    maxThreads: config.threadPoolSize
});

// Handle uncaught exceptions / rejections
process.on('unhandledRejection', (reason, promise) => {
    logger.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('uncaughtException', function (err) {
    logger.error('Unhandled Exception', err);
})

// Excluded urls
excludeUrls = [
    utils.getUrlRegExp(config.baseUrlRegEx, '/scp-international'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/top-rated-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/new-pages-feed'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:recent-changes'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/most-recently-edited'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/most-recently-created'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/lowest-rated-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/guide-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/contribute'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/young-and-under-30'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/seminars-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/site-rules'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:join'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/feed'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum:start'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum:recent-posts'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/chat-guide'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/authors-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/news'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/miss-lohner-s-sandbox'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/how-to-write-an-scp'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/usertools'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/sandbox'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/contact-staff'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/scp-international'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/top-rated-pages-this-month'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/new-pages-feed'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:recent-changes'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/lowest-rated-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/guide-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/contribute'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/young-and-under-30'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/seminars-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/site-rules'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:join'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:list-all-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/feed'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum:start'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/forum:recent-posts'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/chat-guide'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/authors-pages'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/miss-lohner-s-sandbox'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/how-to-write-an-scp'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/usertools'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/sandbox'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/contact-staff'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/tag-search'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/staff-policy-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/criticism-policy'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/deletions-guide'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/faq'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/image-use-policy'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/guide-for-newbies'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/links'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/donations-policy'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/licensing-guide'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/licensing-master-list'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/scp-calendar'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/search:site'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/scp-artwork-hub'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/contest-archive'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/system:page-tags'),
    utils.getUrlRegExp(config.baseUrlRegEx, '/audio-adaptations'),
    utils.getUrlRegExp('http[s]*://www\\.wikidot\\.com/user:info'),
    utils.getUrlRegExp('http[s]*://www\\.youtube\\.com'),
    utils.getUrlRegExp('http[s]*://youtu\\.be'),
    utils.getUrlRegExp('http[s]*://www.youtu\\.be'),
]

// Include the following urls for html files
includeHtmlUrls = [
    utils.getUrlRegExp(config.baseUrlRegEx),
    utils.getUrlRegExp('http[s]*://scp-wiki\\.wdfiles\\.com'),
]

// Keep track of URLs that should be skipped
const skippedUrls = [];
const skippedUrlsFile = 'skipped-urls.txt';

/**
 * Load skipped urls from file
 */
function loadSkippedUrls() {
    if (!fs.existsSync(skippedUrlsFile))
        return;

    var array = fs.readFileSync(skippedUrlsFile).toString().split("\n");
    for (i in array) {
        if (array[i].length == 0)
            continue;
        skippedUrls.push(array[i]);
    }
}

/**
 * Add url to skipped array list / file
 * 
 * @param {string} url - url
 */
function addSkippedUrl(url) {
    skippedUrls.push(url);
    fs.appendFile(skippedUrlsFile, url + '\n', (err) => {
        if (err) throw err;
    });
}

/**
 * Sanitize filename to ensure it only contains supported characters
 * 
 * @param {string} fileName File name to sanitize  
 * @param {string} replacement String to replace unsupported characters
 */
function sanitizeFileName(fileName, replacement) {
    const illegalRe = /[\?<>:\*\|"]/g;
    const controlRe = /[\x00-\x1f\x80-\x9f]/g;
    const windowsReservedRe = /(?<=^|\/|\\)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?=\/|\\|\.|$)/ig;
    const windowsTrailingRe = /[\. ]+$/;

    var sanitized = fileName.replace(illegalRe, replacement)
        .replace(controlRe, replacement)
        .replace(windowsReservedRe, '$1' + replacement)
        .replace(windowsTrailingRe, replacement);
    return sanitized;
}

/**
 * Async http get call
 * 
 * @param {string} url - url
 */
async function get(url) {
    return await got({
        url: url,
        encoding: 'binary',
		https: {
			rejectUnauthorized: false
        },
        retry: 4
    });
}

/**
 * Build list of authors pages to exclude in order to minimize size    
 */
async function ScrapeAuthorsPage() {
    var response = await get(config.baseUrl + '/authors-pages');

    const contentType = response.headers['content-type'];
    if (contentType && contentType.includes('text/html')) {
        const $ = cheerio.load(response.body, {
            decodeEntities: false,
            lowerCaseAttributeNames: false,
        });

        var authorPages = [];
        $('div#page-content').find('td a').each((idx, elem) => {
            if ($(elem).attr('href') && $(elem).attr('href').startsWith('/'))
                authorPages.push($(elem).attr('href'));
        });

        return authorPages;
    }

    return null;
}

/**
 * Matches url on set of supported extension
 * 
 * @param {string} url Input url
 */
function matchExt(url) {
    // Match on extension ending or followed by ? (query)
    const ExtRegEx = /\.(?:gif|jpg|jpeg|png|tif|bmp|mov|mpg|mpeg|avi|asf|mp3|ogg|m4a|mp2|rm|wav|vob|qt|vid|ac3|wma|wmv|css|js|ttf|svg|eot|woff|woff2|pdf)(?:\?|$)/ig
    return url.match(ExtRegEx);
}

/**
 * Plug-in class for scraper
 */
class ScrapePlugin {
    apply(registerAction) {
        /**
         * Generate file name
         */
        registerAction('generateFilename', async ({ resource, responseData }) => {
            // Generate sanitized filename by site structure
            var fileName = bySiteStructureFilenameGenerator(resource, { defaultFilename: 'index.html' });
            fileName = sanitizeFileName(fileName, '_').replace('...............................................', '_______');
            let extension = path.extname(fileName).toLowerCase();

            // Attempt to resolve extension by either mime type or, if that fails, by actual file contents
            if (!extension) {
                if (typeExtensions[resource.getType()]) {
                    extension = typeExtensions[resource.getType()][0];
                } else if (responseData) {
                    if (responseData.mimeType && responseData.mimeType !== 'application/octet-stream') {
                        extension = '.' + mime.extension(responseData.mimeType)
                    }

                    if (!extension) {
                        const type = await fileType.fromBuffer(Buffer.from(responseData.body, 'binary'));
                        extension = type ? '.' + type.ext : null;
                        console.log(`Added extension ${extension} to ${fileName}!`);
                    }
                }

                if (extension) {
                    fileName += extension;
                }
            }
            return {
                filename: fileName
            };
        });

        registerAction('onResourceError', ({ resource, error }) => {
            logger.error(`*** ERROR ***: Resource ${resource.url} has error ${error}.`);
        });

        /**
         * After response processing
         */
        registerAction('afterResponse', async ({ response, url }) => {
            // Skip if 404
            if (response.statusCode === 404) {
                return null;
            }

            // Check if we got an empty body for some reason
            if (!response.body) {
                logger.warn(`Skipped empty url ${url}`);
                //addSkippedUrl(url);
                return null;
            }

            const contentType = response.headers['content-type'];
            if (contentType && contentType.includes('text/html')) {
                // Skip non-html urls that are returned as html
                if (matchExt(url)) {
                    logger.warn(`Skipped non-html url ${url}. Content type: ${contentType}`);
                    //addSkippedUrl(url);
                    return null;
                }

                // Skip html urls that are not in the explicit allow list
                var isUrlMatch = false;
                for (const [index, entry] of includeHtmlUrls.entries()) {
                    if (url.match(entry)) {
                        isUrlMatch = true;
                        break;
                    }
                }
                if (!isUrlMatch) {
                    logger.debug(`Skipped html url ${url}`);
                    //addSkippedUrl(url);
                    return null;
                }

                // Scrape the page
                var ret = await pool.runTask({ level: logger.level, body: response.body, url: url });
                if (ret) {
                    logger.debug(`Processed html ${url}`);
                }
                return ret;
            } else {
                logger.debug(`Processed non-html ${url}`);
                return response.body;
            }
        });
    }
}

// Array of urls to exclude (populated at runtime)
const excludeUrlsDynamic = [];

/**
 * Loads up urls to skip and populates additional urls to skip
 */
async function preProcess() {
    loadSkippedUrls();

    // Skip authors pages
    let authorsPages = await ScrapeAuthorsPage();
    if (authorsPages) {
        for (const [index, entry] of authorsPages.entries()) {
            excludeUrlsDynamic.push(getUrlRegExp(config.baseUrlRegEx, entry));
        }
    }
}

/**
 * Main scrape entry point
 * 
 * @param {string} outputPath - output path
 */
async function scrape(outputPath) {
    const options = {
        urls: [
            config.baseUrl,
            config.baseUrl + '/common--javascript/resize-iframe.html',
        ],

        directory: outputPath,
        recursive: true,
        maxRecursiveDepth: 1000,
        requestConcurrency: config.threadPoolSize,
        ignoreErrors: true,
        filenameGenerator: 'bySiteStructure',
        plugins: [new ScrapePlugin()],
        request: {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.125 Safari/537.36',
            },
            https: {
                rejectUnauthorized: false
            },
            retry: {
                limit: 8,
                errorCodes: [ 'ETIMEDOUT', 'ECONNRESET', 'EADDRINUSE', 'ECONNREFUSED', 'EPIPE', 'ENETUNREACH', 'EAI_AGAIN' ]
            },
            timeout: 60000
        },

        /**
         * Filters urls
         * 
         * @param {string} url - url
         */
        urlFilter: function (url) {
            if (skippedUrls.includes(url)) {
                logger.debug(`skipping url ${url}`);
                return false;
            }
            if (excludeUrls.findIndex(element => url.match(element)) !== -1) {
                return false;
            }
            if (excludeUrlsDynamic.findIndex(element => url.match(element)) !== -1) {
                logger.debug(`skipping dynamic ${url}`);
                return false;
            }
            return true;
        },
    };

    await websiteScraper(options);
}

/**
 * Main entry point
 * 
 * @param {*} argv - args
 */
function main(argv) {
    logger.level = argv.log ?? 'info';

    // Launch scraper
    logger.info(`Launching SCP scraper of '${config.baseUrl}' and output = '${argv.output}'`);

    (async () => {
        try {
            await preProcess();

            // Scrape
            await scrape(argv.output);
            logger.info('******************* DONE *****************');
            process.exit(0);
        } catch (err) {
            logger.error('******************* ERROR *****************');
            logger.error('Error', err);
            process.exit(1);
        }
    })();
}

module.exports = main

/*
setInterval(function () {
    log() // logs out active handles that are keeping node running
}, 5 * 60 * 1000)
*/