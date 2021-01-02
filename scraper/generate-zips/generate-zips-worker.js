const cheerio = require('cheerio');
const fs = require('fs');
const fsP = fs.promises;
const cssUrlParse = require('css-url-parser');
const htmlEntities = require('he');
const path = require('path');

const logger = require('../logger');

// Selectors used to select references
var selectors = [
    { selector: 'style', css: true },
    { selector: '[style]', attr: 'style', css: true },
    { selector: 'a', attr: 'href' },
    { selector: 'img', attr: 'src' },
    { selector: 'input', attr: 'src' },
    { selector: 'object', attr: 'data' },
    { selector: 'embed', attr: 'src' },
    { selector: 'param[name="movie"]', attr: 'value' },
    { selector: 'link[rel*="icon"]', attr: 'href' },
    { selector: 'svg *[xlink\\:href]', attr: 'xlink:href' },
    { selector: 'svg *[href]', attr: 'href' },
    { selector: 'meta[property="og\\:image"]', attr: 'content' },
    { selector: 'meta[property="og\\:image\\:url"]', attr: 'content' },
    { selector: 'meta[property="og\\:image\\:secure_url"]', attr: 'content' },
    { selector: 'meta[property="og\\:audio"]', attr: 'content' },
    { selector: 'meta[property="og\\:audio\\:url"]', attr: 'content' },
    { selector: 'meta[property="og\\:audio\\:secure_url"]', attr: 'content' },
    { selector: 'meta[property="og\\:video"]', attr: 'content' },
    { selector: 'meta[property="og\\:video\\:url"]', attr: 'content' },
    { selector: 'meta[property="og\\:video\\:secure_url"]', attr: 'content' },
    { selector: 'video', attr: 'src' },
    { selector: 'video source', attr: 'src' },
    { selector: 'video track', attr: 'src' },
    { selector: 'audio', attr: 'src' },
    { selector: 'audio source', attr: 'src' },
    { selector: 'audio track', attr: 'src' },
    { selector: 'frame', attr: 'src' },
    { selector: '[background]', attr: 'background' }
]

/**
 * Determines whether given document matches the tag rule
 * 
 * @param {object} $ - cheerio object representing the DOM
 * @param {object} tagRule - tag rule
 * @param {strong} path - path of document
 */
function filterByTagRule($, tagRule, path) {
    // Match by default
    var match = true;

    if (tagRule.tags) {
        if (tagRule.ignoreTagsFor && tagRule.ignoreTagsFor.findIndex(element => path.match(element)) !== -1) {
            // Matched on ignore tag rule
            match = true;
        } else {
            const tag = $('div.page-tags a').filter(function () {
                return (tagRule.tags.findIndex(tagElem => $(this).text().toLowerCase().trim() === tagElem) !== -1);
            });

            match = (tag.length !== 0);
        }
    }

    if (tagRule.pathPattern) {
        match = (tagRule.pathPattern.findIndex(element => path.match(element)) !== -1);
    }

    return match;
}

/**
 * 
 * Determines whether given document matches the tag rules
 * 
 * @param {object} $ - cheerio object representing the DOM
 * @param {array} tagRules - array of tag rules
 * @param {strong} path - path of document
 */
function filterByTagRules($, tagRules, path) {
    for (var i = 0; i < tagRules.length; i++) {
        if (filterByTagRule($, tagRules[i], path)) {
            return tagRules[i].name;
        }
    }

    return null;
}

/**
 * Returns paths to objects referenced by the document
 * 
 * @param {object} $ - cheerio object representing the DOM
 * @param {string} filePath - path of the document
 */
function getPaths($, filePath) {
    const paths = new Set();
    for (var i = 0; i < selectors.length; i++) {
        var rule = selectors[i];
        $(rule.selector).map((i, elem) => {
            const text = rule.attr ? $(elem).attr(rule.attr) : $(elem).text();
            const decodedText = (typeof text === 'string') ? htmlEntities.decode(text) : '';

            if (decodedText) {
                if (rule.css) {
                    cssUrlParse(decodedText).forEach(elem => paths.add(path.resolve(path.dirname(filePath), decodeURI(elem))));
                } else {
                    const isSamePageId = decodedText.startsWith('#');
                    if (!isSamePageId) {
                        var resolvedPath = path.resolve(path.dirname(filePath), decodeURI(decodedText));
                        paths.add(resolvedPath);
                    }
                }
            }
        });
    }

    return paths;
}

/**
 * Worker method that processes given document by filtering on tag rule and returning paths referenced by the document
 */
async function processFile(args) {
    logger.level = args.level;
    const filePath = args.filePath;
    const tagRules = args.tagRules;

    logger.debug(`Processing file for zip generation: ${filePath}`);

    // Read file
    const body = await fsP.readFile(filePath);

    // Load html
    const $ = cheerio.load(body, {
        decodeEntities: false,
        lowerCaseAttributeNames: false,
    });

    // Filter by tag rule
    const path = filePath.replace(/\\/g, '/');
    const tag = filterByTagRules($, tagRules, path);
    if (tag) {
        return { tag: tag, paths: getPaths($, filePath) };
    }

    return null;
}

module.exports = processFile;