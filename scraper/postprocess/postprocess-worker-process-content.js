const cheerio = require('cheerio');
const fs = require('fs');
const fsP = fs.promises;

const logger = require('../logger');

/**
 * Injects css callback into html file
 * 
 * @param {object} $ - cheerio DOM
 */
async function injectCssLink($, filePath) {
    const offline_override_css = '/scpanywhere_inject/override.css';
    const link = `<link rel="stylesheet" href="${offline_override_css}">`;

    // Skip if already added
    var injectionFound = false;
    $(`head link[href="${offline_override_css}"]`).each((idx, elem)=> {
        injectionFound = true;
    });

    if (injectionFound) {
        logger.debug(`Skipping CSS injection (already injected): '${filePath}'`);
        return;
    } else {
        logger.debug(`Injecting CSS link: '${filePath}'`);
    }

    $('head').append($(link));
}

/**
 * Process contents
 * 
 * @param {object} $ - cheerio DOM
 */
async function processContent($, filePath) {
    const title = $('head title').text()

    var rating = Number.parseInt($('span.rate-points span').first().text())
    if (Number.isNaN(rating)) {
        rating = null
    }

    var processTags = true
    var tagNames = []
    var span = $('div.page-tags').first().find('span').first()
    if (!span.length) {
        processTags = false
        span = $('span.scpanywhere_tags').first()
    }
    if (span.length) {
        span.find('a').each((index, element) => {
            tagNames.push($(element).text().toLowerCase().trim())
        })

        if (processTags) {
            span.addClass('scpanywhere_tags')
            span.before('Tags:');
            span.before('<hr>');
            var contents = span.parent().contents()
            span.parent().replaceWith(contents)
        }
    }

    // Nuke all but these two elements
    const ondomready = $('div#dummy-ondomready-block').first()
    const maincontent = $('div#main-content').first()
    $('body').empty()
    $('body').prepend(ondomready)
    $('body').prepend(maincontent)
    return {'title' : title, 'tags' : tagNames, 'rating' : rating}
}

/**
 * Process html file
 * 
 * @param {object} args - args 
 */
async function processHtml(args) {
    logger.level = args.level;
    const filePath = args.filePath;

    var body = null;
    try {
        body = await fsP.readFile(filePath);
    } catch (err) {
        if (err.code === 'ENOENT') {
            logger.warn(`File not found: ${filePath}, skipping`);
            return;
        } else {
            throw err;
        }
    }

    const $ = cheerio.load(body, {
        decodeEntities: false,
        lowerCaseAttributeNames: false,
    });

    var ret = await processContent($, filePath)
    await injectCssLink($, filePath);
    await fsP.writeFile(filePath, $.html());

    return ret
}

module.exports = processHtml;
