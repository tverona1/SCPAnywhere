const cheerio = require('cheerio');
const config = require('../config');
const logger = require('../logger');

// Page tags to always include
const alwaysIncludeTags = ['scp', 'supplement', 'tale'];

// Page tags to exclude
const excludeTags = ['artwork', 'workbench', 'author']

/**
 * Filters given document by tags
 * 
 * @param {object} $ - cheerio object representing the DOM
 * @param {string} url - url
 */
function filterByTag($, url) {
    const pageTags = 'div.page-tags span a';
    const matchInclude = $(pageTags).filter(function () {
        return (alwaysIncludeTags.findIndex(e => $(this).text().toLowerCase().trim() === e) !== -1);
    });
    
    const matchExclude = $(pageTags).filter(function () {
        return (excludeTags.findIndex(e => $(this).text().toLowerCase().trim() === e) !== -1);
    });
    
    return (matchInclude.length>0 || !matchExclude.length);
}

// List of urls to consider as equivalent for html references
const equivalentUrls = [
    new RegExp(/^\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scpcafe.wikidot.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/www\.scp-wiki\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/www\.scp-wiki\.net\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki\.net\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scpwiki\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/fondationscp\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/fondazionescp\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/ja\.scp-wiki\.net\/(.+)/i),
    new RegExp(/^http[s]?:\/\/ko\.scp-wiki\.net\/(.+)/i),
    new RegExp(/^http[s]?:\/\/lafundacionscp\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scpcafe\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-th\.wikidot.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-ukrainian\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-cs\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki-de\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki\.net\.pl\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-pt-br\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-int\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-jp\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-pt-br\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-th\.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki-cn.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki-de.wikidot\.com\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scp-wiki\.net\.pl\/(.+)/i),
    new RegExp(/^http[s]?:\/\/scpfoundation\.net\/(.+)/i),
]

/**
 * Scrapes the given document
 * 
 * @param {object} args - args
 */
function scrapeResponse(args) {
    logger.level = args.level;
    const body = args.body;
    const url = args.url;
    const $ = cheerio.load(body, {
        decodeEntities: false,
        lowerCaseAttributeNames: false,
    });

    if (!filterByTag($, url)) {
        return null;
    }

    // Skip main page's contents
    if (url === config.baseUrl || url === config.baseUrl + '/') {
        $('div#page-content').remove();
    }

    // Remove unneeded content
    $('.info-container').remove();
    $('.scpnet-interwiki-wrapper').remove();
    $('#login-status').remove();
    $('#search-top-box').remove();
    $('#page-options-container').remove();
    $('#footer').remove();
    $('#license-area').remove();
    $('#footer-bar').remove();
    $('.licensebox22').remove();
    $('div.wd-adunit').remove();

    // Clean up scripts
    $('script').each((idx, elem) => {
        if ($(elem).text().includes('google_analytics') ||
            $(elem).text().includes('googletag') ||
            $(elem).text().includes('OneSignal') ||
            $(elem).text().includes('doubleclick') ||
            $(elem).text().includes('quantserve') ||
            $(elem).text().includes('createAd') ||
            $(elem).attr('src') && $(elem).attr('src').includes('onesignal') ||
            $(elem).attr('src') && $(elem).attr('src').includes('nitropay')) {
                $(elem).remove();
            }
    });

    // Clean up top bar
    $('div.top-bar').find('li').each((idx, elem) => {
        if ($(elem).text() && $(elem).text().includes('SCP Global') ||
            $(elem).text() && $(elem).text().includes('Info Pages')) {
            $(elem).remove();
        }
    });

    $('li a[href="/audio-adaptations"]').parent().remove();
    $('li a[href="/scp-artwork-hub"]').parent().remove();
    $('li a[href="/contest-archive"]').parent().remove();

    $('div.mobile-top-bar').find('li').each((idx, elem) => {
        if ($(elem).text() && $(elem).text().includes('Guides')) {
            $(elem).remove();
        }
    });

    // Clean up menu items
    $('div.menu-item').find('a').each((idx, elem) => {
        if ($(elem).attr('href') && $(elem).attr('href').includes('scp-international')) {
            $(elem).parent().remove();
        }
    });

    $('div#side-bar').find('p').each((idx, elem) => {
        if ($(elem).text().includes('Discover Content') ||
            $(elem).text().includes('SCP Community')) {
                $(elem).parent().remove();
            }
    });

    $('div.side-block.media').remove();
    $('div.side-block.resources').remove();
    $('div.scpnet-interwiki-wrapper').remove();
    $('div.t_info').remove();
    $('td.t_info').remove();
    $('div.creditButton').remove();
    $('div.creditButtonStandalone').remove();
    $('div#u-credit-view').remove();

    $('div.page-tags').find('a').each((idx, elem) => {
        if ($(elem).attr('href')) {
            $(elem).attr('href', '#');
        }
    });

    // Clean up rate widget
    $('div.page-rate-widget-box').find('span').each((idx, elem) => {
        if ($(elem).hasClass('rateup') ||
            $(elem).hasClass('ratedown') ||
            $(elem).hasClass('cancel')) {
            $(elem).remove();
        }
    });

    // Clean up side bar
    $('div#side-bar').find('a').each((idx, elem) => {
        if ($(elem).attr('href') && $(elem).attr('href').includes('top-rated-pages-this-month') ||
            $(elem).attr('href') && $(elem).attr('href').includes('new-pages-feed') ||
            $(elem).attr('href') && $(elem).attr('href').includes('random:random-scp') ||
            $(elem).attr('href') && $(elem).attr('href').includes('system:recent-changes') ||
            $(elem).attr('href') && $(elem).attr('href').includes('lowest-rated-pages') ||
            $(elem).attr('href') && $(elem).attr('href').includes('guide-hub') ||
            $(elem).attr('href') && $(elem).attr('href').includes('contribute') ||
            $(elem).attr('href') && $(elem).attr('href').includes('young-and-under-30') ||
            $(elem).attr('href') && $(elem).attr('href').includes('seminars-hub') ||
            $(elem).attr('href') && $(elem).attr('href').includes('site-rules') ||
            $(elem).attr('href') && $(elem).attr('href').includes('system:join') ||
            $(elem).attr('href') && $(elem).attr('href').includes('forum:start') ||
            $(elem).attr('href') && $(elem).attr('href').includes('chat-guide') ||
            $(elem).attr('href') && $(elem).attr('href').includes('authors-pages') ||
            $(elem).attr('href') && $(elem).attr('href').includes('news') ||
            $(elem).attr('href') && $(elem).attr('href').includes('staff-policy-hub')) {
                $(elem).parent().remove();
            }
    });

    // Replace equivalent URLs with the base url
    $('a').each((idx, elem) => {
        var href = $(elem).attr('href');
        if (href) {
            equivalentUrls.forEach(url => {
                const match = href.match(url);
                if (match) {
                    $(elem).attr('href', config.baseUrl + '/' + match[1]);
                }
            });
        }
    });

    return $.html();
}

module.exports = scrapeResponse;
